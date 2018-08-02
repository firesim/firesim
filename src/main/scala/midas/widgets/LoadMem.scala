// See LICENSE for license details.

package midas
package widgets

import chisel3._
import chisel3.util._
import junctions._
import freechips.rocketchip.config.{Parameters, Field}

import scala.math.{max, min}

class LoadMemIO(hKey: Field[NastiParameters])(implicit p: Parameters) extends WidgetIO()(p){
  // TODO: Slave nasti key should be passed in explicitly
  val toSlaveMem = new NastiIO()(p alterPartial ({ case NastiKey => p(hKey) }))
}

class NastiParams()(implicit val p: Parameters) extends HasNastiParameters

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
  val size = hParams.bytesToXSize(UInt(hWidth/8))
  val widthRatio = hWidth/cWidth
  require(hWidth >= cWidth)
  require(p(hKey).addrBits <= 2 * cWidth)

  val wAddrH = genWOReg(Wire(UInt(max(0,  p(hKey).addrBits - 32).W)), "W_ADDRESS_H")
  val wAddrL = genWOReg(Wire(UInt(min(32, p(hKey).addrBits     ).W)), "W_ADDRESS_L")
  val wLen = genWORegInit(Wire(UInt((p(hKey).addrBits - log2Ceil(hWidth/8) + 1).W)), "W_LENGTH", 0.U)
  // When set, instructs the unit to write 0s to the complete address space
  // Cleared when completed
  val zeroOutDram = genAndAttachReg(Wire(Bool()), "ZERO_OUT_DRAM", Some(false.B))

  val wBeatsRemaining = RegInit(0.U(log2Ceil(maxBurst+1).W))
  val nextBurstLen = Mux(wLen > maxBurst.U, maxBurst.U, wLen)
  val wAddr = Cat(wAddrH, wAddrL)

  when (zeroOutDram && wLen === 0.U) {
    when (wBeatsRemaining === 0.U) {
      // Commence initialization by faking a really large write
      wLen := 1.U << (p(hKey).addrBits - log2Ceil(hWidth/8))
      wAddrH := 0.U
      wAddrL := 0.U
    }.elsewhen(io.toSlaveMem.w.fire && io.toSlaveMem.w.bits.last) {
      // We've written the last beat; clear the zeroOutDram bit to indicate doneness
      zeroOutDram := false.B
    }
  }

  io.toSlaveMem.aw.bits := NastiWriteAddressChannel(
      id = 0.U,
      addr = wAddr,
      len = nextBurstLen - 1.U,
      size = size)(p.alterPartial({ case NastiKey => p(hKey) }))

  io.toSlaveMem.aw.valid := wLen > 0.U && wBeatsRemaining === 0.U

  val nextWAddr = wAddr + (nextBurstLen << (log2Ceil(hWidth/8).U))

  when (io.toSlaveMem.aw.fire) {
    wLen := wLen - nextBurstLen
    wBeatsRemaining := nextBurstLen
    if (p(hKey).addrBits > p(CtrlNastiKey).dataBits) {
      wAddrH := nextWAddr(p(hKey).addrBits - 1, 32)
      wAddrL := nextWAddr(31, 0)
    } else {
      wAddrL := nextWAddr(p(hKey).addrBits - 1, 0)
    }
  }

  val wDataQ = Module(new MultiWidthFifo(cWidth, hWidth, maxBurst))
  attachDecoupledSink(wDataQ.io.in, "W_DATA")

  io.toSlaveMem.w.bits := NastiWriteDataChannel(
    data = Mux(zeroOutDram, 0.U, wDataQ.io.out.bits),
    last = wBeatsRemaining === 1.U
  )(p alterPartial ({ case NastiKey => p(hKey) }))

  when (io.toSlaveMem.w.fire) {
    wBeatsRemaining := wBeatsRemaining - 1.U
  }
  io.toSlaveMem.w.valid := (zeroOutDram || wDataQ.io.out.valid) && wBeatsRemaining =/= 0.U
  wDataQ.io.out.ready := !zeroOutDram && io.toSlaveMem.w.ready && wBeatsRemaining =/= 0.U

  // TODO: Handle write responses better?
  io.toSlaveMem.b.ready := Bool(true)

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
