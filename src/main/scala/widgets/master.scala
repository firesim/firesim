package midas_widgets

import junctions._
import cde.{Parameters, Field}
import Chisel._

class EmulationMasterIO(implicit p: Parameters) extends WidgetIO()(p){
  val hostReset = Bool(OUTPUT)
  val simReset = Bool(OUTPUT)
  val done = Bool(INPUT)
  val step = Decoupled(UInt(width = p(CtrlNastiKey).dataBits))
  val traceLen = UInt(OUTPUT, width = p(CtrlNastiKey).dataBits)
}

object Pulsify {
  def apply(in: Bool, pulseLength: Int): Unit = {
    require(pulseLength > 0)
    val count = Counter(pulseLength)
    when(in){count.inc()}
    when(count.value === UInt(pulseLength-1)) {
      in := Bool(false)
      count.value := UInt(0)
    }
  }
}

class EmulationMaster(implicit p: Parameters) extends Widget()(p) {
  val io = new EmulationMasterIO
  Pulsify(genWOReg(io.hostReset, Bool(false), "HOST_RESET"), pulseLength = 4)
  Pulsify(genWOReg(io.simReset, Bool(false), "SIM_RESET"), pulseLength = 4)

  genAndAttachDecoupled(io.step, "STEP")
  genWOReg(io.traceLen, UInt(128), "TRACELEN")
  genROReg(io.done, UInt(0), "DONE")

  genCRFile()
}
