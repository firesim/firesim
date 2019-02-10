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
import junctions.{NastiIO, NastiKey}

object TokenQueueConsts {
  val TOKENS_PER_BIG_TOKEN = 7
  val BIG_TOKEN_WIDTH = (TOKENS_PER_BIG_TOKEN + 1) * 64
  val TOKEN_QUEUE_DEPTH = 6144
}
import TokenQueueConsts._

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

class BIGToken extends Bundle {
  val data = Vec(7, UInt(64.W))
  val rvls = Vec(7, new ReadyValidLast())
  val pad = UInt(43.W)
}

class HostToNICToken extends Bundle {
  val data_in = new StreamChannel(64)
  val data_in_valid = Bool()
  val data_out_ready = Bool()
}

class NICToHostToken extends Bundle {
  val data_out = new StreamChannel(64)
  val data_out_valid = Bool()
  val data_in_ready = Bool()
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
  val hPort = Flipped(HostPort(new NICIOvonly))
}

class BigTokenToNICTokenAdapter extends Module {
  val io = IO(new Bundle {
    val htnt = DecoupledIO(new HostToNICToken)
    val pcie_in = Flipped(DecoupledIO(UInt(512.W)))
  })

  val pcieBundled = (new BIGToken).fromBits(io.pcie_in.bits)

  val xactHelper = DecoupledHelper(io.htnt.ready, io.pcie_in.valid)

  val loopIter = RegInit(0.U(32.W))
  when (io.htnt.fire()) {
    loopIter := Mux(loopIter === 6.U, 0.U, loopIter + 1.U)
  }

  io.htnt.bits.data_in.data := pcieBundled.data(loopIter)
  io.htnt.bits.data_in.keep := 0xFF.U
  io.htnt.bits.data_in.last := pcieBundled.rvls(loopIter).data_last
  io.htnt.bits.data_in_valid := pcieBundled.rvls(loopIter).valid
  io.htnt.bits.data_out_ready := pcieBundled.rvls(loopIter).ready
  io.htnt.valid := xactHelper.fire(io.htnt.ready)
  io.pcie_in.ready := xactHelper.fire(io.pcie_in.valid, loopIter === 6.U)
}

class NICTokenToBigTokenAdapter extends Module {
  val io = IO(new Bundle {
    val ntht = Flipped(DecoupledIO(new NICToHostToken))
    val pcie_out = DecoupledIO(UInt(512.W))
  })

  // step one, buffer 7 elems into registers. note that the 7th element is here 
  // just for convenience. in reality, it is not used since we're bypassing to
  // remove a cycle of latency
  val NTHT_BUF = Reg(Vec(7, new NICToHostToken))
  val specialCounter = RegInit(0.U(32.W))

  when (io.ntht.valid) {
    NTHT_BUF(specialCounter) := io.ntht.bits
  }

  io.ntht.ready := (specialCounter === 6.U && io.pcie_out.ready) || (specialCounter =/= 6.U)
  io.pcie_out.valid := specialCounter === 6.U && io.ntht.valid
  when ((specialCounter =/= 6.U) && io.ntht.valid) {
    specialCounter := specialCounter + 1.U
  } .elsewhen ((specialCounter === 6.U) && io.ntht.valid && io.pcie_out.ready) {
    specialCounter := 0.U
  } .otherwise {
    specialCounter := specialCounter
  }
  // step two, connect 6 elems + latest one to output (7 items)
  // TODO: attach pcie_out to data

  // debug check to help check we're not losing tokens somewhere
  val token_trace_counter = RegInit(0.U(43.W))
  when (io.pcie_out.fire()) {
    token_trace_counter := token_trace_counter + 1.U
  } .otherwise {
    token_trace_counter := token_trace_counter
  }

  val out = Wire(new BIGToken)
  for (i <- 0 until 6) {
    out.data(i) := NTHT_BUF(i).data_out.data
    out.rvls(i).data_last := NTHT_BUF(i).data_out.last
    out.rvls(i).ready := NTHT_BUF(i).data_in_ready
    out.rvls(i).valid := NTHT_BUF(i).data_out_valid
  }
  out.data(6) := io.ntht.bits.data_out.data
  out.rvls(6).data_last := io.ntht.bits.data_out.last
  out.rvls(6).ready := io.ntht.bits.data_in_ready
  out.rvls(6).valid := io.ntht.bits.data_out_valid
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
  lazy val dmaSize = BigInt((BIG_TOKEN_WIDTH / 8) * TOKEN_QUEUE_DEPTH)

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

    target.rlimit := (new RateLimiterSettings).fromBits(rlimitSettings)
    target.macAddr := Cat(macAddrRegUpper, macAddrRegLower)

    attach(macAddrRegUpper, "macaddr_upper", WriteOnly)
    attach(macAddrRegLower, "macaddr_lower", WriteOnly)
    attach(rlimitSettings, "rlimit_settings", WriteOnly)
  }

  genROReg(!tFire, "done")

  genCRFile()
}
