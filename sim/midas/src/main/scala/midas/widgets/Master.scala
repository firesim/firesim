// See LICENSE for license details.

package midas
package widgets


import chisel3._
import freechips.rocketchip.config.Parameters

class SimulationMasterIO(implicit val p: Parameters) extends WidgetIO()(p){
}

class SimulationMaster(implicit p: Parameters) extends Widget()(p) {
  lazy val module = new WidgetImp(this) {
    val io = IO(new SimulationMasterIO)

    val initDelay = RegInit(64.U)
    when (initDelay =/= 0.U) { initDelay := initDelay - 1.U }
    genRORegInit(initDelay === 0.U, "INIT_DONE", 0.U)

    genCRFile()
  }
}
