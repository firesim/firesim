//See LICENSE for license details
package firesim.bridges

import midas.widgets._

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters

class GroundTestBridge extends BlackBox
    with Bridge[HostPortIO[GroundTestBridgeTargetIO], GroundTestBridgeModule] {
  val io = IO(new GroundTestBridgeTargetIO)
  val bridgeIO = HostPort(io)
  val constructorArg = None
  generateAnnotations()
}

object GroundTestBridge {
  def apply(success: Bool)(implicit p: Parameters): GroundTestBridge = {
    val bridge = Module(new GroundTestBridge)
    bridge.io.success := success
    bridge
  }
}

class GroundTestBridgeTargetIO extends Bundle {
  val success = Input(Bool())
}

class GroundTestBridgeModule(implicit p: Parameters)
    extends BridgeModule[HostPortIO[GroundTestBridgeTargetIO]] {
  val io = IO(new WidgetIO)
  val hPort = IO(HostPort(new GroundTestBridgeTargetIO))

  hPort.toHost.hReady := true.B
  hPort.fromHost.hValid := true.B

  val success = RegInit(false.B)

  when (hPort.hBits.success && !success) { success := true.B }

  genROReg(success, "success")
  genCRFile()
}
