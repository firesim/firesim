// See LICENSE for license details.

package midas
package widgets

import chisel3._
import chisel3.util._
import junctions._
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.subsystem.{ExtMem, MasterPortParams}

import scala.math.{max, min}

class LoadMemIO(val hKey: Field[NastiParameters])(implicit val p: Parameters) extends WidgetIO()(p){
  // TODO: Slave nasti key should be passed in explicitly
  val toSlaveMem = new NastiIO()(p alterPartial ({ case NastiKey => p(hKey) }))
}

class NastiParams()(implicit val p: Parameters) extends HasNastiParameters

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

  when (io.req.fire()) {
    wZero := io.req.bits.zero
    wAddr := io.req.bits.addr
    wLen  := io.req.bits.len
    state := s_addr
  }

  when (io.mem.aw.fire()) {
    wBeatsLeft := nextBurstLen - 1.U
    wLen := wLen - nextBurstLen
    wAddr := wAddr + burstBytes
    state := s_data
  }

  when (io.mem.w.fire()) {
    wBeatsLeft := wBeatsLeft - 1.U
    when (wBeatsLeft === 0.U) { state := s_resp }
  }

  when (io.mem.b.fire()) {
    state := Mux(wLen === 0.U, s_idle, s_addr)
  }
}

// A crude load mem unit that writes in single beats into the destination memory system
// Arguments:
//  Hkey -> the Nasti key for the interconnect of the memory system we are writing to
//  maxBurst -> the maximum number of beats in a request made to the host memory system
class LoadMemWidget(hKey: Field[NastiParameters], maxBurst: Int = 8)(implicit p: Parameters) extends Widget()(p) {
  val io = IO(new LoadMemIO(hKey))

  // prefix h -> host memory we are writing to
  // prefix c -> control nasti interface who is the master of this unit
  val hParams = new NastiParams()(p alterPartial ({ case NastiKey => p(hKey) }))
  val cParams = new NastiParams()(p alterPartial ({ case NastiKey => p(CtrlNastiKey) }))

  val cWidth = p(CtrlNastiKey).dataBits
  val hWidth = p(hKey).dataBits
  val size = hParams.bytesToXSize((hWidth/8).U)
  val widthRatio = hWidth/cWidth
  require(hWidth >= cWidth)
  require(p(hKey).addrBits <= 2 * cWidth)

  val wAddrH = genWOReg(Wire(UInt(max(0,  p(hKey).addrBits - 32).W)), "W_ADDRESS_H")
  val wAddrL = genWOReg(Wire(UInt(min(32, p(hKey).addrBits     ).W)), "W_ADDRESS_L")
  val wLen = Wire(Decoupled(UInt(p(hKey).addrBits.W)))
  // When set, instructs the unit to write 0s to the complete address space
  // Cleared when completed
  val zeroOutDram = Wire(Decoupled(Bool()))

  attachDecoupledSink(wLen, "W_LENGTH")
  attachDecoupledSink(zeroOutDram, "ZERO_OUT_DRAM")

  val hAlterP = p.alterPartial({ case NastiKey => p(hKey) })
  val wAddrQ = Module(new Queue(new LoadMemWriteRequest()(hAlterP), 2))
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
      size = BigInt(1L << p(hKey).addrBits),
      beatBytes = hWidth/8,
      idBits = p(hKey).idBits)
  }

  val reqArb = Module(new Arbiter(new LoadMemWriteRequest()(hAlterP), 2))
  reqArb.io.in(0) <> wAddrQ.io.deq
  reqArb.io.in(1).valid := zeroOutDram.valid
  reqArb.io.in(1).bits.zero := true.B
  reqArb.io.in(1).bits.addr := extMem.base.U
  reqArb.io.in(1).bits.len  := (extMem.size >> log2Ceil(hWidth/8)).U
  zeroOutDram.ready := reqArb.io.in(1).ready

  val writer = Module(new LoadMemWriter(maxBurst)(hAlterP))
  writer.io.req <> reqArb.io.out
  io.toSlaveMem.aw <> writer.io.mem.aw
  io.toSlaveMem.w  <> writer.io.mem.w
  writer.io.mem.b <> io.toSlaveMem.b
  writer.io.mem.ar.ready := false.B
  writer.io.mem.r.valid := false.B
  writer.io.mem.r.bits := DontCare
  writer.io.data <> wDataQ.io.out

  attach(writer.io.req.ready, "ZERO_FINISHED", ReadOnly)

  val rAddrH = genWOReg(Wire(UInt(max(0, p(hKey).addrBits - 32).W)), "R_ADDRESS_H")
  val rAddrQ = genAndAttachQueue(Wire(Decoupled(UInt(p(hKey).addrBits.W))), "R_ADDRESS_L")

  io.toSlaveMem.ar.bits := NastiReadAddressChannel(
      id = 0.U,
      addr = Cat(rAddrH, rAddrQ.bits),
      size = size)(p alterPartial ({ case NastiKey => p(hKey) }))
  io.toSlaveMem.ar.valid := rAddrQ.valid
  rAddrQ.ready := io.toSlaveMem.ar.ready

  val rDataQ = Module(new MultiWidthFifo(hWidth, cWidth, 2))
  attachDecoupledSource(rDataQ.io.out, "R_DATA")
  io.toSlaveMem.r.ready := rDataQ.io.in.ready
  rDataQ.io.in.valid := io.toSlaveMem.r.valid
  rDataQ.io.in.bits := io.toSlaveMem.r.bits.data

  genCRFile()

  override def genHeader(base: BigInt, sb: StringBuilder) {
    super.genHeader(base, sb)
    import CppGenerationUtils._
    sb.append(genMacro("MEM_DATA_CHUNK", UInt64(
      (p(hKey).dataBits - 1) / p(midas.core.ChannelWidth) + 1)))
  }
}
