//See LICENSE for license details
package firesim.bridges

import midas.widgets._

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters

case class CoreTemperatureBridgeParams(tempWidth: Int)

class CoreTemperatureBridgeTargetIO(val tempWidth: Int) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Bool())
  val tileCycleCount = Input(UInt(32.W))
  val instructionsRetired = Input(UInt(32.W))
  val temperature = Output(UInt(tempWidth.W))
}
class CoreTemperatureBridge(tempWidth: Int) extends BlackBox
    with Bridge[HostPortIO[CoreTemperatureBridgeTargetIO], CoreTemperatureBridgeModule] {
  val io = IO(new CoreTemperatureBridgeTargetIO(tempWidth))
  val bridgeIO = HostPort(io)
  val constructorArg = Some(CoreTemperatureBridgeParams(tempWidth))
  generateAnnotations()
}

class CoreTemperatureBridgeModule(key: CoreTemperatureBridgeParams)(implicit p: Parameters) extends BridgeModule[HostPortIO[CoreTemperatureBridgeTargetIO]]()(p) {
  lazy val module = new BridgeModuleImp(this) {
    require(key.tempWidth <= 32)
    val io = IO(new WidgetIO())
    val hPort = IO(HostPort(new CoreTemperatureBridgeTargetIO(key.tempWidth)))

    val doneInit = Wire(Bool())
    val updateInterval = Wire(UInt(32.W))
    val tCycle = RegInit(0.U(32.W))
    val resetCount = Wire(Bool())
    val idle = updateInterval === tCycle

    genWOReg(updateInterval, "update_interval")
    genWORegInit(doneInit, "done_init", false.B)
    Pulsify(genWORegInit(resetCount, "reset_count", false.B), pulseLength = 1)
    genROReg(idle, "idle")
    genROReg(hPort.hBits.tileCycleCount, "tile_cycle_count")
    genROReg(hPort.hBits.instructionsRetired, "instructions_retired")
    genWOReg(hPort.hBits.temperature, "temperature")

    val fire = hPort.toHost.hValid &&
               hPort.fromHost.hReady &&
               doneInit &&
               !idle

    when(resetCount) {
      tCycle := 0.U
    }.elsewhen(fire) {
      tCycle := tCycle + 1.U
    }

    hPort.toHost.hReady := fire
    hPort.fromHost.hValid := fire
    genCRFile()
  }
}
