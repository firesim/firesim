package midas_widgets

import chisel3._
import chisel3.util.{Decoupled, Counter}
import junctions._
import cde.{Parameters, Field}

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
    if (pulseLength > 1) {
      val count = Counter(pulseLength)
      when(in){count.inc()}
      when(count.value === UInt(pulseLength-1)) {
        in := Bool(false)
        count.value := UInt(0)
      }
    } else {
      when(in) {in := Bool(false)}
    }
  }
}

class EmulationMaster(implicit p: Parameters) extends Widget()(p) {
  val io = IO(new EmulationMasterIO)
  Pulsify(genWORegInit(io.hostReset,"HOST_RESET", Bool(false)), pulseLength = 4)
  Pulsify(genWORegInit(io.simReset, "SIM_RESET", Bool(false)), pulseLength = 1)

  genAndAttachQueue(io.step, "STEP")
  genRORegInit(io.done, "DONE", UInt(0))
  if (p(strober.EnableSnapshot)) {
    genWORegInit(io.traceLen, "TRACELEN", UInt(128))
  }

  genCRFile()
}
