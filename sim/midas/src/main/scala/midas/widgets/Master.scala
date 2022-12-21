// See LICENSE for license details.

package midas
package widgets

import junctions._

import chisel3._
import chisel3.util.{Decoupled, Counter, log2Up, Cat}
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._

class SimulationMasterIO(implicit val p: Parameters) extends WidgetIO()(p){
  val done = Input(Bool())
  val step = Decoupled(UInt(p(CtrlNastiKey).dataBits.W))
}

object Pulsify {
  def apply(in: Bool, pulseLength: Int): Unit = {
    require(pulseLength > 0)
    if (pulseLength > 1) {
      val count = Counter(pulseLength)
      when(in){count.inc()}
      when(count.value === (pulseLength - 1).U) {
        in := false.B
        count.value := 0.U
      }
    } else {
      when(in) {in := false.B}
    }
  }
}


class SimulationMaster(implicit p: Parameters) extends Widget()(p) {
  lazy val module = new WidgetImp(this) {
    val io = IO(new SimulationMasterIO)
    genAndAttachQueue(io.step, "STEP")
    genRORegInit(io.done, "DONE", 0.U)

    val initDelay = RegInit(64.U)
    when (initDelay =/= 0.U) { initDelay := initDelay - 1.U }
    genRORegInit(initDelay === 0.U, "INIT_DONE", 0.U)

    genCRFile()
  }
}

class TokenHashMasterIO(implicit val p: Parameters) extends WidgetIO()(p){
  val triggerDelay = Output(UInt(64.W))
  val triggerPeriod = Output(UInt(64.W))
}

class TokenHashMaster(implicit p: Parameters) extends Widget()(p) {
  lazy val module = new WidgetImp(this) {
    val io = IO(new TokenHashMasterIO)

    val delay0  = genWORegInit(Wire(UInt(32.W)), s"triggerDelay0_${name}", 16.U)
    val delay1  = genWORegInit(Wire(UInt(32.W)), s"triggerDelay1_${name}", 0.U)
    
    io.triggerDelay := Cat(Seq(delay1, delay0))

    val period0 = genWORegInit(Wire(UInt(32.W)), s"triggerPeriod0_${name}", 1.U)
    val period1 = genWORegInit(Wire(UInt(32.W)), s"triggerPeriod1_${name}", 0.U)

    io.triggerPeriod := Cat(Seq(period1, period0))

    genCRFile()
  }
}