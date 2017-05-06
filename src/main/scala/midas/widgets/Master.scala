package midas
package widgets

// from rocketchip
import junctions._

import chisel3._
import chisel3.util.{Decoupled, Counter, log2Up}
import config.Parameters

class EmulationMasterIO(implicit p: Parameters) extends WidgetIO()(p){
  val simReset = Output(Bool())
  val done = Input(Bool())
  val step = Decoupled(UInt(p(CtrlNastiKey).dataBits.W))
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
  Pulsify(genWORegInit(io.simReset, "SIM_RESET", Bool(false)), pulseLength = 4)
  genAndAttachQueue(io.step, "STEP")
  genRORegInit(io.done && ~io.simReset, "DONE", UInt(0))

  genCRFile()

  override def genHeader(base: BigInt, sb: StringBuilder) {
    import CppGenerationUtils._
    super.genHeader(base, sb)
    sb.append(genMacro("CHANNEL_SIZE", UInt32(log2Up(p(midas.core.ChannelWidth)/8))))
  }
}
