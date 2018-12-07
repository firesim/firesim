// See LICENSE for license details.

package midas
package widgets

import core.{HostPort, HostPortIO, MemNastiKey, DMANastiKey}
import junctions._

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.util.{DecoupledHelper}

abstract class EndpointWidgetIO(implicit p: Parameters) extends WidgetIO()(p) {
  def hPort: HostPortIO[Data] // Tokenized port moving between the endpoint the target-RTL
  val dma: Option[NastiIO] = None
  // Specify the size that you want the address region to be in the DMA memory map
  // For proper functioning, the region should be at least as big as the
  // largest read/write request you plan to send over PCIS
  val dmaSize: BigInt = BigInt(0)
  val tReset = Flipped(Decoupled(Bool()))
}

abstract class EndpointWidget(implicit p: Parameters) extends Widget()(p) {
  override def io: EndpointWidgetIO
}

abstract class MemModelConfig // TODO: delete it

class MemModelIO(implicit val p: Parameters) extends EndpointWidgetIO()(p){
  // The default NastiKey is expected to be that of the target
  val tNasti = Flipped(HostPort(new NastiIO, false))
  val host_mem = new NastiIO()(p.alterPartial({ case NastiKey => p(MemNastiKey)}))
  def hPort = tNasti
}

trait HasDMA {
  self: EndpointWidget =>
  val dma = IO(Flipped(new NastiIO()( p.alterPartial({ case NastiKey => p(DMANastiKey) }))))
  val dmaBytes = dma.nastiXDataBits / 8
  val dmaSize: BigInt
}

trait DMAToHostCPU extends HasDMA {
  self: EndpointWidget =>
  val toHostCPUQueueDepth: Int

  require(dma.nastiXDataBits == 512, "DMA width must be 512 bits (PCIS)")

  //val incomingPCISdat = Module(new SplitSeqQueue)
  val outgoingPCISdat = Module(new BRAMQueue(toHostCPUQueueDepth)(UInt(dma.nastiXDataBits.W)))

  // incoming/outgoing queue counts to replace ready/valid for batching
  val outgoingCount = RegInit(0.U(32.W))

  when (outgoingPCISdat.io.enq.fire() && outgoingPCISdat.io.deq.fire()) {
    outgoingCount := outgoingCount
  } .elsewhen (outgoingPCISdat.io.enq.fire()) {
    outgoingCount := outgoingCount + 1.U
  } .elsewhen (outgoingPCISdat.io.deq.fire()) {
    outgoingCount := outgoingCount - 1.U
  } .otherwise {
    outgoingCount := outgoingCount
  }

  // check to see if pcis has valid output instead of waiting for timeouts
  attach(outgoingPCISdat.io.deq.valid, "pcis_out_valid", ReadOnly)
  attach(outgoingCount, "outgoing_count", ReadOnly)

  // TODO, will these queues bottleneck us? - Biancolin: no. -> just read out of BRAM queue?
  val ar_queue = Queue(dma.ar, 10)

  assert(!ar_queue.valid || ar_queue.bits.size === log2Ceil(dmaBytes).U)

  val readHelper = DecoupledHelper(
    ar_queue.valid,
    dma.r.ready,
    outgoingPCISdat.io.deq.valid
  )

  val readBeatCounter = RegInit(0.U(9.W))
  val lastReadBeat = readBeatCounter === ar_queue.bits.len
  when (dma.r.fire()) {
    readBeatCounter := Mux(lastReadBeat, 0.U, readBeatCounter + 1.U)
  }

  outgoingPCISdat.io.deq.ready := readHelper.fire(outgoingPCISdat.io.deq.valid)

  dma.r.valid := readHelper.fire(dma.r.ready)
  dma.r.bits.data := outgoingPCISdat.io.deq.bits
  dma.r.bits.resp := 0.U(2.W)
  dma.r.bits.last := lastReadBeat
  dma.r.bits.id := ar_queue.bits.id
  dma.r.bits.user := ar_queue.bits.user
  ar_queue.ready := readHelper.fire(ar_queue.valid, lastReadBeat)
}

trait DMAFromHostCPU extends HasDMA {
  self: EndpointWidget =>
  val fromHostCPUQueueDepth: Int

  require(dma.nastiXDataBits == 512, "DMA width must be 512 bits (PCIS)")

  val incomingPCISdat = Module(new BRAMQueue(fromHostCPUQueueDepth)(UInt(dma.nastiXDataBits.W)))

  // incoming/outgoing queue counts to replace ready/valid for batching
  val incomingCount = RegInit(0.U(32.W))

  when (incomingPCISdat.io.enq.fire() && incomingPCISdat.io.deq.fire()) {
    incomingCount := incomingCount
  } .elsewhen (incomingPCISdat.io.enq.fire()) {
    incomingCount := incomingCount + 1.U
  } .elsewhen (incomingPCISdat.io.deq.fire()) {
    incomingCount := incomingCount - 1.U
  } .otherwise {
    incomingCount := incomingCount
  }

  // check to see if pcis is ready to accept data instead of forcing writes
  attach(incomingPCISdat.io.deq.valid, "pcis_in_busy", ReadOnly)
  attach(incomingCount, "incoming_count", ReadOnly)

  val aw_queue = Queue(dma.aw, 10)
  val w_queue = Queue(dma.w, 10)

  assert(!aw_queue.valid || aw_queue.bits.size === log2Ceil(dmaBytes).U)
  assert(!w_queue.valid  || w_queue.bits.strb === ~0.U(dmaBytes.W))

  val writeHelper = DecoupledHelper(
    aw_queue.valid,
    w_queue.valid,
    dma.b.ready,
    incomingPCISdat.io.enq.ready
  )

  val writeBeatCounter = RegInit(0.U(9.W))
  val lastWriteBeat = writeBeatCounter === aw_queue.bits.len
  when (w_queue.fire()) {
    writeBeatCounter := Mux(lastWriteBeat, 0.U, writeBeatCounter + 1.U)
  }

  dma.b.bits.resp := 0.U(2.W)
  dma.b.bits.id := aw_queue.bits.id
  dma.b.bits.user := aw_queue.bits.user
  dma.b.valid := writeHelper.fire(dma.b.ready, lastWriteBeat)
  aw_queue.ready := writeHelper.fire(aw_queue.valid, lastWriteBeat)
  w_queue.ready := writeHelper.fire(w_queue.valid)

  incomingPCISdat.io.enq.valid := writeHelper.fire(incomingPCISdat.io.enq.ready)
  incomingPCISdat.io.enq.bits := w_queue.bits.data
}


trait TieOffDMAToHostCPU extends HasDMA {
  self: EndpointWidget =>
  dma.ar.ready := false.B
  dma.r.valid := false.B
  dma.r.bits := DontCare
}

trait TieOffDMAFromHostCPU extends HasDMA{
  self: EndpointWidget =>
  dma.aw.ready := false.B
  dma.w.ready := false.B
  dma.b.valid := false.B
  dma.b.bits := DontCare
}

trait UnidirectionalDMAToHostCPU extends HasDMA with DMAToHostCPU with TieOffDMAFromHostCPU {
  self: EndpointWidget =>
}

trait UnidirecitonalDMAFromHostCPU extends HasDMA with DMAFromHostCPU with TieOffDMAToHostCPU {
  self: EndpointWidget =>
}

trait BidirectionalDMA extends HasDMA with DMAFromHostCPU with DMAToHostCPU {
  self: EndpointWidget =>
}


abstract class MemModel(implicit p: Parameters) extends EndpointWidget()(p){
  val io = IO(new MemModelIO)
  override def genHeader(base: BigInt, sb: StringBuilder) {
    super.genHeader(base, sb)
    import CppGenerationUtils._
    sb.append(genMacro(this.getClass.getSimpleName))
  }
}

abstract class NastiWidgetBase(implicit p: Parameters) extends MemModel {
  val tNasti = io.hPort.hBits
  val tReset = io.tReset.bits
  val tFire = io.hPort.toHost.hValid && io.hPort.fromHost.hReady && io.tReset.valid

  // Buffers for NASTI(AXI) channels
  val arBuf = Module(new Queue(new NastiReadAddressChannel,   8, flow=true))
  val awBuf = Module(new Queue(new NastiWriteAddressChannel,  8, flow=true))
  val wBuf  = Module(new Queue(new NastiWriteDataChannel,    16, flow=true))
  val rBuf  = Module(new Queue(new NastiReadDataChannel,     16, flow=true))
  val bBuf  = Module(new Queue(new NastiWriteResponseChannel, 8, flow=true))

  // 1. define firing condition with "stall"
  // 2. handle target reset
  // 3. connect target channels to buffers.
  //   - Transactions are generated / consumed according to timing conditions
  def elaborate(stall: Bool,
                rCycleValid: Bool = true.B,
                wCycleValid: Bool = true.B,
                rCycleReady: Bool = true.B,
                wCycleReady: Bool = true.B) = {
    val fire = tFire && !stall
    fire suggestName "fire"
    io.tNasti.toHost.hReady := fire
    io.tNasti.fromHost.hValid := fire
    io.tReset.ready := fire

    // Bad assumption: We have no outstanding read or write requests to host
    // during target reset. This will be handled properly in the fully fledged
    // memory model; i'm too lazy to properly handle this here.
    val targetReset = fire && tReset
    targetReset suggestName "targetReset"
    arBuf.reset := reset.toBool || targetReset
    awBuf.reset := reset.toBool || targetReset
    rBuf.reset := reset.toBool || targetReset
    bBuf.reset := reset.toBool || targetReset
    wBuf.reset := reset.toBool || targetReset

    // Request
    tNasti.ar.ready    := arBuf.io.enq.ready && rCycleReady
    tNasti.aw.ready    := awBuf.io.enq.ready && wCycleReady
    tNasti.w.ready     := wBuf.io.enq.ready  && wCycleReady
    arBuf.io.enq.valid := tNasti.ar.valid && rCycleReady && fire && !tReset
    awBuf.io.enq.valid := tNasti.aw.valid && wCycleReady && fire && !tReset
    wBuf.io.enq.valid  := tNasti.w.valid  && wCycleReady && fire && !tReset
    arBuf.io.enq.bits  := tNasti.ar.bits
    awBuf.io.enq.bits  := tNasti.aw.bits
    wBuf.io.enq.bits   := tNasti.w.bits

    // Response
    tNasti.r.bits     := rBuf.io.deq.bits
    tNasti.b.bits     := bBuf.io.deq.bits
    tNasti.r.valid    := rBuf.io.deq.valid && rCycleValid
    tNasti.b.valid    := bBuf.io.deq.valid && wCycleValid
    rBuf.io.deq.ready := tNasti.r.ready && fire && rCycleValid
    bBuf.io.deq.ready := tNasti.b.ready && fire && wCycleValid

    val cycles = Reg(UInt(64.W))
    cycles suggestName "cycles"
    when (fire) {
      cycles := Mux(targetReset, 0.U, cycles + 1.U)
    }

    (fire, cycles, targetReset)
  }
}

// Widget to handle NastiIO efficiently when software memory timing models are used
class NastiWidget(implicit p: Parameters) extends NastiWidgetBase {
  /*** Timing information ***/
  // deltas from the simulation driver are kept in "deltaBuf"
  val deltaBuf = Module(new Queue(UInt(32.W), 2))
  // counters
  val delta = Reg(UInt(32.W))
  val readCount = Reg(UInt(32.W))
  val writeCount = Reg(UInt(32.W))
  // The widget stalls when
  // 1. there are any outstanding reads/writes
  // 2. delta == 0
  val stall = !delta.orR && (readCount.orR || writeCount.orR)
  val (fire, cycles, targetReset) = elaborate(stall)

  deltaBuf.io.deq.ready := stall
  when(reset.toBool || targetReset) {
    delta := 0.U
  }.elsewhen(deltaBuf.io.deq.valid && stall) {
    // consume "deltaBuf" with stall
    delta := deltaBuf.io.deq.bits
  }.elsewhen(fire && delta.orR) {
    // decrement delta with firing condition
    delta := delta - 1.U
  }

  // Set outstanding read counts
  when(reset.toBool || targetReset) {
    readCount := 0.U
  }.elsewhen(tNasti.ar.fire() && fire) {
    readCount := readCount + 1.U
  }.elsewhen(rBuf.io.enq.fire() && rBuf.io.enq.bits.last) {
    readCount := readCount - 1.U
  }

  // Set outstanding write counts
  when(reset.toBool || targetReset) {
    writeCount := 0.U
  }.elsewhen(tNasti.w.fire() && tNasti.w.bits.last && fire) {
    writeCount := writeCount + 1.U
  }.elsewhen(bBuf.io.enq.fire()) {
    writeCount := writeCount - 1.U
  }

  // Disable host_mem
  io.host_mem.ar.valid := false.B
  io.host_mem.aw.valid := false.B
  io.host_mem.w.valid := false.B
  io.host_mem.r.ready := false.B
  io.host_mem.b.ready := false.B

  // Generate memory-mapped registers for the read address channel
  val arMeta = Seq(arBuf.io.deq.bits.id, arBuf.io.deq.bits.size, arBuf.io.deq.bits.len)
  val arMetaWidth = (arMeta foldLeft 0)(_ + _.getWidth)
  assert(arMetaWidth <= io.ctrl.nastiXDataBits)
  if (tNasti.ar.bits.addr.getWidth + arMetaWidth <= io.ctrl.nastiXDataBits) {
    genROReg(Cat(arBuf.io.deq.bits.addr +: arMeta), "ar_bits")
  } else {
    genROReg(arBuf.io.deq.bits.addr, "ar_addr")
    genROReg(Cat(arMeta), "ar_meta")
  }

  // Generate memory-mapped registers for the write address channel
  val awMeta = Seq(awBuf.io.deq.bits.id, awBuf.io.deq.bits.size, awBuf.io.deq.bits.len)
  val awMetaWidth = (awMeta foldLeft 0)(_ + _.getWidth)
  assert(awMetaWidth <= io.ctrl.nastiXDataBits)
  if (tNasti.aw.bits.addr.getWidth + awMetaWidth <= io.ctrl.nastiXDataBits) {
    genROReg(Cat(awBuf.io.deq.bits.addr +: awMeta), "aw_bits")
  } else {
    genROReg(awBuf.io.deq.bits.addr, "aw_addr")
    genROReg(Cat(awMeta), "aw_meta")
  }

  // Generate memory-mapped registers for the write data channel
  val wMeta = Seq(wBuf.io.deq.bits.strb, wBuf.io.deq.bits.last)
  val wMetaWidth = (wMeta foldLeft 0)(_ + _.getWidth)
  assert(wMetaWidth <= io.ctrl.nastiXDataBits)
  genROReg(Cat(wMeta), "w_meta")
  val wdataChunks = (tNasti.w.bits.nastiXDataBits - 1) / io.ctrl.nastiXDataBits + 1
  val wdataRegs = Seq.fill(wdataChunks)(Reg(UInt()))
  val wdataAddrs = wdataRegs.zipWithIndex map {case (reg, i) =>
    reg := wBuf.io.deq.bits.data >> (io.ctrl.nastiXDataBits*i).U
    attach(reg, s"w_data_$i")
  }

  // Generate memory-mapped registers for the read data channel
  val rMeta = Seq(rBuf.io.deq.bits.id, rBuf.io.deq.bits.resp, rBuf.io.deq.bits.last)
  val rMetaWidth = (rMeta foldLeft 0)(_ + _.getWidth)
  val rMetaReg = Reg(UInt(rMetaWidth.W))
  assert(rMetaWidth <= io.ctrl.nastiXDataBits)
  attach(rMetaReg, "r_meta")
  rBuf.io.enq.bits.id := rMetaReg >> (tNasti.r.bits.resp.getWidth + 1).U
  rBuf.io.enq.bits.resp := rMetaReg >> 1.U
  rBuf.io.enq.bits.last := rMetaReg(0)
  val rdataChunks = (tNasti.r.bits.nastiXDataBits - 1) / io.ctrl.nastiXDataBits + 1
  val rdataRegs = Seq.fill(rdataChunks)(Reg(UInt()))
  val rdataAddrs = rdataRegs.zipWithIndex map {case (reg, i) => attach(reg, s"r_data_$i")}
  rBuf.io.enq.bits.data := Cat(rdataRegs.reverse)

  // Generate memory-mapped registers for the write response channel
  val bMeta = Seq(bBuf.io.deq.bits.id, bBuf.io.deq.bits.resp)
  val bMetaWidth = (bMeta foldLeft 0)(_ + _.getWidth)
  val bMetaReg = Reg(UInt(bMetaWidth.W))
  assert(bMetaWidth <= io.ctrl.nastiXDataBits)
  attach(bMetaReg, "b_meta")
  bBuf.io.enq.bits.id := bMetaReg >> (tNasti.b.bits.resp.getWidth).U
  bBuf.io.enq.bits.resp := bMetaReg

  // Generate memory-mapped registers for ready/valid
  genROReg(Cat(
    arBuf.io.deq.valid,
    awBuf.io.deq.valid,
    wBuf.io.deq.valid,
    rBuf.io.enq.ready,
    bBuf.io.enq.ready), "valid")
  val readyReg = RegInit(0.U(5.W))
  arBuf.io.deq.ready := readyReg(4)
  awBuf.io.deq.ready := readyReg(3)
  wBuf.io.deq.ready := readyReg(2)
  rBuf.io.enq.valid := readyReg(1)
  bBuf.io.enq.valid := readyReg(0)
  attach(readyReg, "ready")
  when(readyReg.orR) { readyReg := 0.U }

  // Generate memory-mapped registers for control signals
  genROReg(!tFire, "done")
  genROReg(stall && !deltaBuf.io.deq.valid, "stall")
  // Connect "deltaBuf" to the control register file
  // Timing information is provided through this
  attachDecoupledSink(deltaBuf.io.enq, "delta")

  genCRFile()

  override def genHeader(base: BigInt, sb: StringBuilder) {
    super.genHeader(base, sb)
    import CppGenerationUtils._
    val name = getWName.toUpperCase
    sb.append(genArray(s"${name}_w_data", wdataAddrs map (off => UInt32(base + off)))) 
    sb.append(genArray(s"${name}_r_data", rdataAddrs map (off => UInt32(base + off)))) 
  }
}
