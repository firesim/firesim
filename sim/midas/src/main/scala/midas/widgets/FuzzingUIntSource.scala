//See LICENSE for license details
package midas.widgets

import chisel3._

import org.chipsalliance.cde.config.Parameters

import firesim.lib.bridgeutils._

//Note: This file is heavily commented as it serves as a bridge walkthrough
//example in the FireSim docs

class FuzzingUIntSourceTargetIO(val uWidth: Int) extends Bundle {
  val clock = Input(Clock())
  val uint  = Output(UInt(uWidth.W))
}

case class FuzzingUIntSourceKey(width: Int)

class FuzzingUIntSourceBridge(width: Int) extends BlackBox with Bridge[HostPortIO[FuzzingUIntSourceTargetIO]] {
  val moduleName     = "midas.widgets.FuzzingUIntSourceBridgeModule"
  val io             = IO(new FuzzingUIntSourceTargetIO(width))
  val bridgeIO       = HostPort(io)
  val constructorArg = Some(FuzzingUIntSourceKey(width))
  generateAnnotations()
}

class FuzzingUIntSourceBridgeModule(key: FuzzingUIntSourceKey)(implicit p: Parameters)
    extends BridgeModule[HostPortIO[FuzzingUIntSourceTargetIO]]()(p) {
  lazy val module = new BridgeModuleImp(this) {
    val io    = IO(new WidgetIO())
    val hPort = IO(HostPort(new FuzzingUIntSourceTargetIO(key.width)))
    hPort.fromHost.hValid := true.B
    hPort.toHost.hReady   := true.B
    hPort.hBits.uint      := chisel3.util.random.LFSR(key.width, hPort.fromHost.hReady)

    io.ctrl := DontCare

    override def genHeader(base: BigInt, memoryRegions: Map[String, BigInt], sb: StringBuilder): Unit = {}
  }
}
