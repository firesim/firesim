// See LICENSE for license details.

package midas.widgets

import chisel3._
import chisel3.util._
import junctions._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.subsystem.{ExtMem, MasterPortParams}

import scala.math.{max, min}

class LoadMemIO(implicit val p: Parameters) extends WidgetIO()(p)

class LoadMemWriteRequest(implicit p: Parameters) extends NastiBundle {
  val zero = Bool()
  val addr = UInt(nastiXAddrBits.W)
  val len = UInt(nastiXAddrBits.W)
}

class LoadMemWriter(maxBurst: Int)(implicit p: Parameters) extends NastiModule {
  val io = IO(new Bundle {
    val req = Flipped(Decoupled(new LoadMemWriteRequest))
    val data = Flipped(Decoupled(UInt(nastiXDataBits.W)))
    val mem = new NastiIO
  })

  val wZero = Reg(Bool())
  val wAddr = Reg(UInt(nastiXAddrBits.W))
  val wLen = Reg(UInt(nastiXAddrBits.W))
  val wSize = log2Ceil(nastiXDataBits/8).U
  val wBeatsLeft = RegInit(0.U(log2Ceil(maxBurst).W))
  val nextBurstLen = Mux(wLen > maxBurst.U, maxBurst.U, wLen)
  val burstBytes = nextBurstLen << wSize

  val (s_idle :: s_addr :: s_data :: s_resp :: Nil) = Enum(4)
  val state = RegInit(s_idle)

  io.req.ready := state === s_idle

  io.mem.aw.valid := state === s_addr
  io.mem.aw.bits := NastiWriteAddressChannel(
    id = 0.U,
    addr = wAddr,
    len = nextBurstLen - 1.U,
    size = wSize)

  io.data.ready := (state === s_data) && !wZero && io.mem.w.ready
  io.mem.w.valid := (state === s_data) && (wZero || io.data.valid)
  io.mem.w.bits := NastiWriteDataChannel(
    data = Mux(wZero, 0.U, io.data.bits),
    last = wBeatsLeft === 0.U)

  io.mem.b.ready := state === s_resp
  io.mem.ar.valid := false.B
  io.mem.ar.bits := DontCare
  io.mem.r.ready := false.B

  when (io.req.fire) {
    wZero := io.req.bits.zero
    wAddr := io.req.bits.addr
    wLen  := io.req.bits.len
    state := s_addr
  }

  when (io.mem.aw.fire) {
    wBeatsLeft := nextBurstLen - 1.U
    wLen := wLen - nextBurstLen
    wAddr := wAddr + burstBytes
    state := s_data
  }

  when (io.mem.w.fire) {
    wBeatsLeft := wBeatsLeft - 1.U
    when (wBeatsLeft === 0.U) { state := s_resp }
  }

  when (io.mem.b.fire) {
    state := Mux(wLen === 0.U, s_idle, s_addr)
  }
}

class LoadMemWidget(val totalDRAMAllocated: BigInt)(implicit p: Parameters) extends Widget()(p) {
  val toHostMemory = AXI4MasterNode(
    Seq(AXI4MasterPortParameters(
      masters = Seq(AXI4MasterParameters(
        name = "Host LoadMem Unit",
        id   = IdRange(0, 1))))))

  lazy val module = new WidgetImp(this) {
  val (memAXI4, edge) = toHostMemory.out.head
  val maxBurst = edge.slave.slaves.map(s => min(s.supportsRead.max, s.supportsWrite.max)).min / (edge.bundle.dataBits / 8)
  val io = IO(new LoadMemIO)
  // Gives us a bi-directional hook to a nasti interface so we don't have to port all the code below
  val memNasti = AXI42Nasti.fromSink(memAXI4)

  // prefix h -> host memory we are writing to
  // prefix c -> control nasti interface who is the master of this unit
  val hKey = NastiParameters(memAXI4.params)
  val hParams = p alterPartial ({ case NastiKey => hKey })
  val cParams = p alterPartial ({ case NastiKey => p(CtrlNastiKey) })

  val cWidth = p(CtrlNastiKey).dataBits
  val hWidth = hKey.dataBits
  val size = log2Ceil(hWidth/8).U
  val widthRatio = hWidth/cWidth
  require(hWidth >= cWidth)
  require(hKey.addrBits <= 2 * cWidth)

  val wAddrH = genWOReg(Wire(UInt(max(0,  hKey.addrBits - 32).W)), "W_ADDRESS_H")
  val wAddrL = genWOReg(Wire(UInt(min(32, hKey.addrBits     ).W)), "W_ADDRESS_L")
  val wLen = Wire(Decoupled(UInt(hKey.addrBits.W)))
  // When set, instructs the unit to write 0s to the complete address space
  // Cleared when completed
  val zeroOutDram = Wire(Decoupled(Bool()))

  attachDecoupledSink(wLen, "W_LENGTH")
  attachDecoupledSink(zeroOutDram, "ZERO_OUT_DRAM")

  val wAddrQ = Module(new Queue(new LoadMemWriteRequest()(hParams), 2))
  wAddrQ.io.enq.valid := wLen.valid
  wAddrQ.io.enq.bits.zero := false.B
  wAddrQ.io.enq.bits.addr := Cat(wAddrH, wAddrL)
  wAddrQ.io.enq.bits.len  := wLen.bits
  wLen.ready := wAddrQ.io.enq.ready

  val wDataQ = Module(new MultiWidthFifo(cWidth, hWidth, maxBurst))
  attachDecoupledSink(wDataQ.io.in, "W_DATA")

  val extMem = p(ExtMem) match {
    case Some(memPortParams) => memPortParams.master
    case None => MasterPortParams(
      base = BigInt(0),
      size = BigInt(1L << hKey.addrBits),
      beatBytes = hWidth/8,
      idBits = hKey.idBits)
  }

  val reqArb = Module(new Arbiter(new LoadMemWriteRequest()(hParams), 2))
  reqArb.io.in(0) <> wAddrQ.io.deq
  reqArb.io.in(1).valid := zeroOutDram.valid
  reqArb.io.in(1).bits.zero := true.B
  reqArb.io.in(1).bits.addr := extMem.base.U
  reqArb.io.in(1).bits.len  := (totalDRAMAllocated >> log2Ceil(hWidth/8)).U
  zeroOutDram.ready := reqArb.io.in(1).ready

  val writer = Module(new LoadMemWriter(maxBurst)(hParams))
  writer.io.req <> reqArb.io.out
  memNasti.aw <> writer.io.mem.aw
  memNasti.w  <> writer.io.mem.w
  writer.io.mem.b <> memNasti.b
  writer.io.mem.ar.ready := false.B
  writer.io.mem.r.valid := false.B
  writer.io.mem.r.bits := DontCare
  writer.io.data <> wDataQ.io.out

  attach(writer.io.req.ready, "ZERO_FINISHED", ReadOnly)

  val rAddrH = genWOReg(Wire(UInt(max(0, hKey.addrBits - 32).W)), "R_ADDRESS_H")
  val rAddrQ = genAndAttachQueue(Wire(Decoupled(UInt(hKey.addrBits.W))), "R_ADDRESS_L")

  memNasti.ar.bits := NastiReadAddressChannel(
      id = 0.U,
      addr = Cat(rAddrH, rAddrQ.bits),
      size = size)(p alterPartial ({ case NastiKey => hKey }))
  memNasti.ar.valid := rAddrQ.valid
  rAddrQ.ready := memNasti.ar.ready

  val rDataQ = Module(new MultiWidthFifo(hWidth, cWidth, 2))
  attachDecoupledSource(rDataQ.io.out, "R_DATA")
  memNasti.r.ready := rDataQ.io.in.ready
  rDataQ.io.in.valid := memNasti.r.valid
  rDataQ.io.in.bits := memNasti.r.bits.data

  genCRFile()

  def memDataChunk: Long =
    ((hKey.dataBits - 1) / p(CtrlNastiKey).dataBits) + 1

  override def genHeader(base: BigInt, memoryRegions: Map[String, BigInt], sb: StringBuilder): Unit = {
    super.genHeader(base, memoryRegions, sb)
    import CppGenerationUtils._
    sb.append(genConstStatic(s"${getWName.toUpperCase}_mem_data_chunk", UInt32(memDataChunk)))
  }
  }
}
