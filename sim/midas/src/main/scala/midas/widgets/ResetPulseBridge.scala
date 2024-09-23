//See LICENSE for license details

package midas.widgets

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters

import firesim.lib.bridges.{ResetPulseBridgeHostIO, ResetPulseBridgeParameters}
import firesim.lib.bridgeutils._

class ResetPulseBridgeModule(cfg: ResetPulseBridgeParameters)(implicit p: Parameters)
    extends BridgeModule[ResetPulseBridgeHostIO]()(p) {
  lazy val module = new BridgeModuleImp(this) {
    val io    = IO(new WidgetIO())
    val hPort = IO(new ResetPulseBridgeHostIO())

    val remainingPulseLength = genWOReg(Wire(UInt(log2Ceil(cfg.maxPulseLength + 1).W)), "pulseLength")
    val pulseComplete        = remainingPulseLength === 0.U
    val doneInit             = genWORegInit(Wire(Bool()), "doneInit", false.B)

    hPort.reset.valid := doneInit
    hPort.reset.bits  := pulseComplete ^ cfg.activeHigh.B

    when(hPort.reset.fire) {
      remainingPulseLength := Mux(pulseComplete, 0.U, remainingPulseLength - 1.U)
    }

    override def genHeader(base: BigInt, memoryRegions: Map[String, BigInt], sb: StringBuilder): Unit = {
      genConstructor(
        base,
        sb,
        "reset_pulse_t",
        "reset_pulse",
        Seq(
          UInt32(cfg.maxPulseLength),
          UInt32(cfg.defaultPulseLength),
        ),
      )
    }
    genCRFile()
  }
}
