// See LICENSE for license details.

package midas
package widgets

import midas.core.{UnpackedWrapperConfig, SimWrapperKey}

import junctions._

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy._

/**
  * When set, enables a running log of simulation time steps and their associated host clock cycles
  */
case object MasterPrintfEnableKey extends Field[Boolean](false)

class SimulationMasterIO(implicit val p: Parameters) extends WidgetIO()(p) with midas.core.UnpackedWrapperConfig {
  def wrapperConfig = p(SimWrapperKey)
  val done = Input(Bool())
  val step = Decoupled(UInt(p(CtrlNastiKey).dataBits.W))
  val hubControl = new midas.core.HubControlInterface(ctrlAnno.params)
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

class SimulationMaster(implicit p: Parameters) extends Widget()(p) with HasTimestampConstants {
  lazy val module = new WidgetImp(this) {
    val io = IO(new SimulationMasterIO)
    genAndAttachQueue(io.step, "STEP")
    genRORegInit(io.done, "DONE", 0.U)

    val initDelay = RegInit(64.U)
    when (initDelay =/= 0.U) { initDelay := initDelay - 1.U }
    genRORegInit(initDelay === 0.U, "INIT_DONE", 0.U)

    val hCycleName = "hCycle"
    val hCycle = genWideRORegInit(0.U(64.W), hCycleName)
    hCycle := hCycle + 1.U

    val timeHorizonH = genWORegInit(Wire(UInt(32.W)), "timeHorizon_h", 0.U)
    val timeHorizonL = genWORegInit(Wire(UInt(32.W)), "timeHorizon_l", 0.U)
    io.hubControl.timeHorizon := Cat(timeHorizonH, timeHorizonL)

    val simTimeReg = genWideRORegInit(0.U(timestampWidth.W), "simulationTime")
    simTimeReg := io.hubControl.simTime

    val activeCycles = genWideRORegInit(0.U(48.W), "activeCycles")
    when (io.hubControl.simAdvancing && io.hubControl.scheduledClocks.orR) {
      activeCycles := activeCycles + 1.U
    }

    val cycleCounts = io.hubControl.scheduledClocks.toBools map { clockEn =>
      val count = RegInit(0.U(48.W))
      when (io.hubControl.simAdvancing && clockEn) { count := count + 1.U }
      count
    }
    val largestCount = cycleCounts.reduce((a, b) => Mux(a > b, a, b))
    genWideRORegInit(0.U(48.W), "tCycle") := largestCount

    if (p(MasterPrintfEnableKey)) {
      when(io.hubControl.simAdvancing) {
        printf("[Master] Host Cycle: %d, New Time: %d, Idle: %b\n", hCycle, io.hubControl.simTime, !io.hubControl.scheduledClocks)
      }
    }

    genCRFile()
  }
}
