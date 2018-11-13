package firesim
package endpoints

import chisel3.core._
import chisel3.util._
import chisel3.Module
import DataMirror.directionOf
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.util._

import midas.core._
import midas.widgets._
import testchipip.{StreamIO, StreamChannel}
import icenet.{NICIOvonly, RateLimiterSettings}
import icenet.IceNIC._
import junctions.{NastiIO, NastiKey}

case object LoopbackNIC extends Field[Boolean]

class SplitSeqQueue(implicit p: Parameters) extends Module {
  /* hacks. the version of FIRRTL we're using can't handle >= 512-bit-wide
     stuff. there are a variety of reasons to not fix it this way, but I just
     want to keep building this
  */
  val EXTERNAL_WIDTH = 512
  val io = IO(new Bundle {
    val enq = Flipped(DecoupledIO(UInt(EXTERNAL_WIDTH.W)))
    val deq = DecoupledIO(UInt(EXTERNAL_WIDTH.W))
  })

  val SPLITS = 1
  val INTERNAL_WIDTH = EXTERNAL_WIDTH / SPLITS
  val DEPTH = 6144

  val voq = VecInit(Seq.fill(SPLITS)(Module((new BRAMQueue(DEPTH)){ UInt(INTERNAL_WIDTH.W) } ).io))

  val enqHelper = new DecoupledHelper(
    io.enq.valid +: voq.map(_.enq.ready))

  io.enq.ready := enqHelper.fire(io.enq.valid)

  for (i <- 0 until SPLITS) {
    voq(i).enq.valid := enqHelper.fire(voq(i).enq.ready)
    voq(i).enq.bits := io.enq.bits((i+1)*INTERNAL_WIDTH-1, i*INTERNAL_WIDTH)
  }

  val deqHelper = new DecoupledHelper(
    io.deq.ready +: voq.map(_.deq.valid))

  for (i <- 0 until SPLITS) {
    voq(i).deq.ready := deqHelper.fire(voq(i).deq.valid)
  }
  io.deq.bits := Cat(voq.map(_.deq.bits).reverse)
  io.deq.valid := deqHelper.fire(io.deq.ready)
}

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
      directionOf(channel.out.valid) == ActualDirection.Output
    case _ => false
  }
  def widget(p: Parameters) = new SimpleNICWidget()(p)
  override def widgetName = "SimpleNICWidget"
}

class SimpleNICWidgetIO(implicit p: Parameters) extends EndpointWidgetIO()(p) {
  val hPort = Flipped(HostPort(new NICIOvonly))
  val dma = if (!p(LoopbackNIC)) {
    Some(Flipped(new NastiIO()(
      p.alterPartial({ case NastiKey => p(DMANastiKey) }))))
  } else None
  val address = if (!p(LoopbackNIC))
    Some(AddressSet(0x00, BigInt("FFFFFFFF", 16))) else None
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

class SimpleNICWidget(implicit p: Parameters) extends EndpointWidget()(p) {
  val io = IO(new SimpleNICWidgetIO)

  val htnt_queue = Module(new Queue(new HostToNICToken, 10))
  val ntht_queue = Module(new Queue(new NICToHostToken, 10))

  val bigtokenToNIC = Module(new BigTokenToNICTokenAdapter)
  val NICtokenToBig = Module(new NICTokenToBigTokenAdapter)

  val incomingPCISdat = Module(new SplitSeqQueue)
  val outgoingPCISdat = Module(new SplitSeqQueue)

  // incoming/outgoing queue counts to replace ready/valid for batching
  val incomingCount = RegInit(0.U(32.W))
  val outgoingCount = RegInit(0.U(32.W))

  when (incomingPCISdat.io.enq.fire() && incomingPCISdat.io.deq.fire()) {
    incomingCount := incomingCount
  } .elsewhen (incomingPCISdat.io.enq.fire()) {
    incomingCount := incomingCount + 1.U
  } .elsewhen (incomingPCISdat.io.deq.fire()) {
    incomingCount := incomingCount - 1.U
  } .otherwise {
    incomingCount := incomingCount
  }

  when (outgoingPCISdat.io.enq.fire() && outgoingPCISdat.io.deq.fire()) {
    outgoingCount := outgoingCount
  } .elsewhen (outgoingPCISdat.io.enq.fire()) {
    outgoingCount := outgoingCount + 1.U
  } .elsewhen (outgoingPCISdat.io.deq.fire()) {
    outgoingCount := outgoingCount - 1.U
  } .otherwise {
    outgoingCount := outgoingCount
  }

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

  // check to see if pcis has valid output instead of waiting for timeouts
  attach(outgoingPCISdat.io.deq.valid, "pcis_out_valid", ReadOnly)
  // check to see if pcis is ready to accept data instead of forcing writes
  attach(incomingPCISdat.io.deq.valid, "pcis_in_busy", ReadOnly)

  attach(outgoingCount, "outgoing_count", ReadOnly)
  attach(incomingCount, "incoming_count", ReadOnly)

  genROReg(!tFire, "done")

  genCRFile()

  val PCIS_BYTES = 64

  io.dma.map { dma =>
    // TODO, will these queues bottleneck us?
    val aw_queue = Queue(dma.aw, 10)
    val w_queue = Queue(dma.w, 10)
    val ar_queue = Queue(dma.ar, 10)

    assert(!ar_queue.valid || ar_queue.bits.size === log2Ceil(PCIS_BYTES).U)
    assert(!aw_queue.valid || aw_queue.bits.size === log2Ceil(PCIS_BYTES).U)
    assert(!w_queue.valid  || w_queue.bits.strb === ~0.U(PCIS_BYTES.W))

    val writeHelper = DecoupledHelper(
      aw_queue.valid,
      w_queue.valid,
      dma.b.ready,
      incomingPCISdat.io.enq.ready
    )

    val readHelper = DecoupledHelper(
      ar_queue.valid,
      dma.r.ready,
      outgoingPCISdat.io.deq.valid
    )

    val writeBeatCounter = RegInit(0.U(9.W))
    val lastWriteBeat = writeBeatCounter === aw_queue.bits.len
    when (w_queue.fire()) {
      writeBeatCounter := Mux(lastWriteBeat, 0.U, writeBeatCounter + 1.U)
    }

    val readBeatCounter = RegInit(0.U(9.W))
    val lastReadBeat = readBeatCounter === ar_queue.bits.len
    when (dma.r.fire()) {
      readBeatCounter := Mux(lastReadBeat, 0.U, readBeatCounter + 1.U)
    }

    dma.b.bits.resp := 0.U(2.W)
    dma.b.bits.id := aw_queue.bits.id
    dma.b.bits.user := aw_queue.bits.user
    dma.b.valid := writeHelper.fire(dma.b.ready, lastWriteBeat)
    aw_queue.ready := writeHelper.fire(aw_queue.valid, lastWriteBeat)
    w_queue.ready := writeHelper.fire(w_queue.valid)

    incomingPCISdat.io.enq.valid := writeHelper.fire(incomingPCISdat.io.enq.ready)
    incomingPCISdat.io.enq.bits := w_queue.bits.data

    outgoingPCISdat.io.deq.ready := readHelper.fire(outgoingPCISdat.io.deq.valid)

    dma.r.valid := readHelper.fire(dma.r.ready)
    dma.r.bits.data := outgoingPCISdat.io.deq.bits
    dma.r.bits.resp := 0.U(2.W)
    dma.r.bits.last := lastReadBeat
    dma.r.bits.id := ar_queue.bits.id
    dma.r.bits.user := ar_queue.bits.user
    ar_queue.ready := readHelper.fire(ar_queue.valid, lastReadBeat)
  }

  //when (outgoingPCISdat.io.enq.fire()) {
  //  printf("outgoing ENQ FIRE\n")
  //}

  //when (outgoingPCISdat.io.deq.fire()) {
  //  printf("outgoing DEQ FIRE\n")
  //}

  //when (incomingPCISdat.io.enq.fire()) {
  //  printf("incoming ENQ FIRE\n")
  //}

  //when (incomingPCISdat.io.deq.fire()) {
  //  printf("incoming DEQ FIRE\n")
  //}
}
