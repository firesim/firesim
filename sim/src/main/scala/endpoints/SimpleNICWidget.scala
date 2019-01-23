package firesim
package endpoints

import chisel3._
import chisel3.util._
import chisel3.experimental.{DataMirror, Direction}
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.util._

import midas.core.{HostPort}
import midas.widgets._
import testchipip.{StreamIO, StreamChannel}
import icenet.{NICIOvonly, RateLimiterSettings}
import icenet.IceNIC._
import icenet.IceNetConsts._
import icenet.IceNetConfig
import junctions.{NastiIO, NastiKey}

object TokenQueueConsts {
  val BIGTOKEN_WIDTH_BITS = 512
  val TOKEN_QUEUE_DEPTH = 6144
}
import TokenQueueConsts._

case class SimpleNICWidgetConfig(TOKEN_WIDTH_BITS: Int = 64){
  val TOKENS_PER_BIGTOKEN = (BIGTOKEN_WIDTH_BITS / (TOKEN_WIDTH_BITS + 3))
  val BIGTOKEN_PADDING = (BIGTOKEN_WIDTH_BITS - (TOKENS_PER_BIGTOKEN * (TOKEN_WIDTH_BITS + 3)))
}

case object NICWidgetKey extends Field[SimpleNICWidgetConfig]

case object LoopbackNIC extends Field[Boolean]

/* on a NIC token transaction:
 * 1) simulation driver feeds an empty token to start:
 *  data_in is garbage or real value (if exists)
 *  data_in_valid is 0 or 1 respectively
 *  data_out_ready is true (say host can always accept)
 *
 * 2) target responds:
 *  data_out garbage or real value (if exists)
 *  data_out_valid 0 or 1 respectively
 *  data_in_ready would be 1, so driver knows how to construct the next token if there was data to send
 *
 *  repeat
 */

class ReadyValidLast extends Bundle {
  val data_last = Bool()
  val ready = Bool()
  val valid = Bool()
}

class BIGToken(implicit p: Parameters) extends Bundle {
  val data = Vec(p(NICWidgetKey).TOKENS_PER_BIGTOKEN, UInt(p(NICWidgetKey).TOKEN_WIDTH_BITS.W))
  val rvls = Vec(p(NICWidgetKey).TOKENS_PER_BIGTOKEN, new ReadyValidLast())
  val pad = UInt(p(NICWidgetKey).BIGTOKEN_PADDING.W)

  override def cloneType: this.type = (new BIGToken).asInstanceOf[this.type]
}

class HostToNICToken(implicit p: Parameters) extends Bundle {
  val data_in = new StreamChannel(p(NICWidgetKey).TOKEN_WIDTH_BITS)
  val data_in_valid = Bool()
  val data_out_ready = Bool()

  override def cloneType: this.type = (new HostToNICToken).asInstanceOf[this.type]
}

class NICToHostToken(implicit p: Parameters) extends Bundle {
  val data_out = new StreamChannel(p(NICWidgetKey).TOKEN_WIDTH_BITS)
  val data_out_valid = Bool()
  val data_in_ready = Bool()

  override def cloneType: this.type = (new NICToHostToken).asInstanceOf[this.type]
}

class SimSimpleNIC extends Endpoint {
  def matchType(data: Data) = data match {
    case channel: NICIOvonly =>
      DataMirror.directionOf(channel.out.valid) == Direction.Output
    case _ => false
  }
  def widget(p: Parameters) = new SimpleNICWidget()(p)
  override def widgetName = "SimpleNICWidget"
}

class SimpleNICWidgetIO(implicit val p: Parameters) extends EndpointWidgetIO()(p) {
  val netConfig = new IceNetConfig(NET_IF_WIDTH_BITS = p(NICWidgetKey).TOKEN_WIDTH_BITS)
  val hPort = Flipped(HostPort(new NICIOvonly(netConfig)))
}

/**
 * Take a BigToken, split it into individual tokens and return it one by one
 */
class BigTokenToNICTokenAdapter(implicit p: Parameters) extends Module {

  val config = p(NICWidgetKey)

  val io = IO(new Bundle {
    val htnt = DecoupledIO(new HostToNICToken)
    val pcie_in = Flipped(DecoupledIO(UInt(BIGTOKEN_WIDTH_BITS.W)))
  })

  val pcieBundled = (new BIGToken).fromBits(io.pcie_in.bits)

  val xactHelper = DecoupledHelper(io.htnt.ready, io.pcie_in.valid)

  val loopIter = RegInit(0.U(32.W))
  when (io.htnt.fire()) {
    loopIter := Mux(loopIter === (config.TOKENS_PER_BIGTOKEN - 1).U, 0.U, loopIter + 1.U)
  }

  io.htnt.bits.data_in.data := pcieBundled.data(loopIter)
  io.htnt.bits.data_in.keep := ((BigInt(1) << (config.TOKEN_WIDTH_BITS/8)) - 1).U
  io.htnt.bits.data_in.last := pcieBundled.rvls(loopIter).data_last
  io.htnt.bits.data_in_valid := pcieBundled.rvls(loopIter).valid
  io.htnt.bits.data_out_ready := pcieBundled.rvls(loopIter).ready
  io.htnt.valid := xactHelper.fire(io.htnt.ready)
  io.pcie_in.ready := xactHelper.fire(io.pcie_in.valid, loopIter === (config.TOKENS_PER_BIGTOKEN - 1).U)
}

/**
 * Take multiple NICTokens and convert them into a single BigToken to send over PCIE
 */
class NICTokenToBigTokenAdapter(implicit p: Parameters) extends Module {

  val config = p(NICWidgetKey)

  val io = IO(new Bundle {
    val ntht = Flipped(DecoupledIO(new NICToHostToken))
    val pcie_out = DecoupledIO(UInt(BIGTOKEN_WIDTH_BITS.W))
  })

  // step one, buffer config.TOKENS_PER_BIGTOKEN elems into registers. note that the last element is here 
  // just for convenience. in reality, it is not used since we're bypassing to
  // remove a cycle of latency
  val NTHT_BUF = Reg(Vec(config.TOKENS_PER_BIGTOKEN, new NICToHostToken))
  val specialCounter = RegInit(0.U(32.W))

  when (io.ntht.valid) {
    NTHT_BUF(specialCounter) := io.ntht.bits
  }

  io.ntht.ready := (specialCounter === (config.TOKENS_PER_BIGTOKEN - 1).U && io.pcie_out.ready) || (specialCounter =/= (config.TOKENS_PER_BIGTOKEN - 1).U)
  io.pcie_out.valid := specialCounter === (config.TOKENS_PER_BIGTOKEN - 1).U && io.ntht.valid
  when ((specialCounter =/= (config.TOKENS_PER_BIGTOKEN - 1).U) && io.ntht.valid) {
    specialCounter := specialCounter + 1.U
  } .elsewhen ((specialCounter === (config.TOKENS_PER_BIGTOKEN - 1).U) && io.ntht.valid && io.pcie_out.ready) {
    specialCounter := 0.U
  } .otherwise {
    specialCounter := specialCounter
  }
  // step two, connect (config.TOKENS_PER_BIGTOKEN - 1) elems + latest one to output (config.TOKENS_PER_BIGTOKEN items)
  // TODO: attach pcie_out to data

  // debug check to help check we're not losing tokens somewhere
  val token_trace_counter = RegInit(0.U(config.BIGTOKEN_PADDING.W))
  when (io.pcie_out.fire()) {
    token_trace_counter := token_trace_counter + 1.U
  } .otherwise {
    token_trace_counter := token_trace_counter
  }

  val out = Wire(new BIGToken)
  for (i <- 0 until (config.TOKENS_PER_BIGTOKEN - 1)) {
    out.data(i) := NTHT_BUF(i).data_out.data
    out.rvls(i).data_last := NTHT_BUF(i).data_out.last
    out.rvls(i).ready := NTHT_BUF(i).data_in_ready
    out.rvls(i).valid := NTHT_BUF(i).data_out_valid
  }
  out.data((config.TOKENS_PER_BIGTOKEN - 1))           := io.ntht.bits.data_out.data
  out.rvls((config.TOKENS_PER_BIGTOKEN - 1)).data_last := io.ntht.bits.data_out.last
  out.rvls((config.TOKENS_PER_BIGTOKEN - 1)).ready     := io.ntht.bits.data_in_ready
  out.rvls((config.TOKENS_PER_BIGTOKEN - 1)).valid     := io.ntht.bits.data_out_valid
  out.pad := token_trace_counter

  io.pcie_out.bits := out.asUInt
}

class HostToNICTokenGenerator(nTokens: Int)(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val out = Decoupled(new HostToNICToken)
    val in = Flipped(Decoupled(new NICToHostToken))
  })

  val s_init :: s_seed :: s_forward :: Nil = Enum(3)
  val state = RegInit(s_init)

  val (_, seedDone) = Counter(state === s_seed && io.out.fire(), nTokens)

  io.out.valid := state === s_seed || (state === s_forward && io.in.valid)
  io.out.bits.data_in_valid := state === s_forward && io.in.bits.data_out_valid
  io.out.bits.data_in := io.in.bits.data_out
  io.out.bits.data_out_ready := state === s_seed || io.in.bits.data_in_ready
  io.in.ready := state === s_forward && io.out.ready

  when (state === s_init) { state := s_seed }
  when (seedDone) { state := s_forward }
}

class SimpleNICWidget(implicit p: Parameters) extends EndpointWidget()(p)
    with BidirectionalDMA {
  val io = IO(new SimpleNICWidgetIO)

  // DMA mixin parameters
  lazy val fromHostCPUQueueDepth = TOKEN_QUEUE_DEPTH
  lazy val toHostCPUQueueDepth   = TOKEN_QUEUE_DEPTH
  // Biancolin: Need to look into this
  lazy val dmaSize = BigInt((BIGTOKEN_WIDTH_BITS / 8) * TOKEN_QUEUE_DEPTH)

  val htnt_queue = Module(new Queue(new HostToNICToken, 10))
  val ntht_queue = Module(new Queue(new NICToHostToken, 10))

  val bigtokenToNIC = Module(new BigTokenToNICTokenAdapter)
  val NICtokenToBig = Module(new NICTokenToBigTokenAdapter)

  val target = io.hPort.hBits
  val tFire = io.hPort.toHost.hValid && io.hPort.fromHost.hReady && io.tReset.valid
  val targetReset = tFire & io.tReset.bits
  io.tReset.ready := tFire

//  htnt_queue.reset  := reset //|| targetReset
//  ntht_queue.reset := reset //|| targetReset

  if (p(LoopbackNIC)) {
    val tokenGen = Module(new HostToNICTokenGenerator(10))
    htnt_queue.io.enq <> tokenGen.io.out
    tokenGen.io.in <> ntht_queue.io.deq
    NICtokenToBig.io.ntht.valid := false.B
    NICtokenToBig.io.ntht.bits := DontCare
    bigtokenToNIC.io.htnt.ready := false.B
  } else {
    NICtokenToBig.io.ntht <> ntht_queue.io.deq
    htnt_queue.io.enq <> bigtokenToNIC.io.htnt
  }

  io.hPort.toHost.hReady := ntht_queue.io.enq.ready
  ntht_queue.io.enq.valid := io.hPort.toHost.hValid
  ntht_queue.io.enq.bits.data_out := target.out.bits
  ntht_queue.io.enq.bits.data_out_valid := target.out.valid
  ntht_queue.io.enq.bits.data_in_ready := true.B //target.in.ready

  io.hPort.fromHost.hValid := htnt_queue.io.deq.valid
  htnt_queue.io.deq.ready := io.hPort.fromHost.hReady
  target.in.bits := htnt_queue.io.deq.bits.data_in
  target.in.valid := htnt_queue.io.deq.bits.data_in_valid
  //target.out.ready := htnt_queue.io.deq.bits.data_out_ready

  bigtokenToNIC.io.pcie_in <> incomingPCISdat.io.deq
  outgoingPCISdat.io.enq <> NICtokenToBig.io.pcie_out


  if (p(LoopbackNIC)) {
    target.rlimit.size := 8.U
    target.rlimit.period := 0.U
    target.rlimit.inc := 1.U
    target.macAddr := 0.U
  } else {
    val macAddrRegUpper = Reg(UInt(32.W))
    val macAddrRegLower = Reg(UInt(32.W))
    val rlimitSettings = Reg(UInt(32.W))
    val netConfig = new IceNetConfig(NET_IF_WIDTH_BITS = p(NICWidgetKey).TOKEN_WIDTH_BITS)

    target.rlimit := (new RateLimiterSettings(netConfig)).fromBits(rlimitSettings)
    target.macAddr := Cat(macAddrRegUpper, macAddrRegLower)

    attach(macAddrRegUpper, "macaddr_upper", WriteOnly)
    attach(macAddrRegLower, "macaddr_lower", WriteOnly)
    attach(rlimitSettings, "rlimit_settings", WriteOnly)
  }

  genROReg(!tFire, "done")

  genCRFile()
}
