package midas_widgets

import junctions._
import cde.{Parameters, Field}
import Chisel._

class LoadMemIO(hKey: Field[NastiParameters])(implicit p: Parameters) extends WidgetIO()(p){
  // TODO: Slave nasti key should be passed in explicitly
  val toSlaveMem = new NastiIO()(p alter Map(NastiKey -> p(hKey)))
}

class NastiParams()(implicit val p: Parameters) extends HasNastiParameters

// A crude load mem unit that writes in single beats into the destination memory system
// Arguments:
//  Hkey -> the Nasti key for the interconnect of the memory system we are writing to
class LoadMemWidget(hKey: Field[NastiParameters])(implicit p: Parameters) extends Widget()(p) {
  val io = IO(new LoadMemIO(hKey))

  // prefix h -> host memory we are writing to
  // prefix c -> control nasti interface who is the master of this unit
  val hParams = new NastiParams()(p alter Map(NastiKey -> p(hKey)))
  val cParams = new NastiParams()(p alter Map(NastiKey -> p(CtrlNastiKey)))

  val cWidth = p(CtrlNastiKey).dataBits
  val hWidth = p(hKey).dataBits
  val size = hParams.bytesToXSize(UInt(hWidth/8))
  val widthRatio = hWidth/cWidth
  require(hWidth >= cWidth)
  require(p(hKey).addrBits <= cWidth)

  val wAddrQ = genAndAttachQueue(Wire(Decoupled(UInt(width = p(hKey).addrBits))), "W_ADDRESS")
  io.toSlaveMem.aw.bits := NastiWriteAddressChannel(
      id = UInt(0),
      addr = wAddrQ.bits,
      size = size)(p alter Map(NastiKey -> p(hKey)))
  io.toSlaveMem.aw.valid := wAddrQ.valid
  wAddrQ.ready := io.toSlaveMem.aw.ready

  val wDataQ = Module(new MultiWidthFifo(cWidth, hWidth, 2))
  attachDecoupledSink(wDataQ.io.in, "W_DATA")

  io.toSlaveMem.w.bits := NastiWriteDataChannel(data = wDataQ.io.out.bits)(
      p alter Map(NastiKey -> p(hKey)))
  io.toSlaveMem.w.valid := wDataQ.io.out.valid
  wDataQ.io.out.ready := io.toSlaveMem.w.ready

  // TODO: Handle write responses better?
  io.toSlaveMem.b.ready := Bool(true)

  val rAddrQ = genAndAttachQueue(Wire(Decoupled(UInt(width = p(hKey).addrBits))), "R_ADDRESS")
  io.toSlaveMem.ar.bits := NastiReadAddressChannel(
      id = UInt(0),
      addr = rAddrQ.bits,
      size = size)(p alter Map(NastiKey -> p(hKey)))
  io.toSlaveMem.ar.valid := rAddrQ.valid

  val rDataQ = Module(new MultiWidthFifo(hWidth, cWidth, 2))
  attachDecoupledSource(rDataQ.io.out, "R_DATA")
  io.toSlaveMem.r.ready := rDataQ.io.in.ready
  rDataQ.io.in.valid := io.toSlaveMem.r.valid

  genCRFile()
}
