// See LICENSE for license details.

package midas.widgets

import midas.core.{SimWrapperChannels, SimUtils}
import midas.core.SimUtils.{RVChTuple}
import midas.passes.fame.{FAMEChannelConnectionAnnotation, TargetClockChannel}

import freechips.rocketchip.config.Parameters
import freechips.rocketchip.util.DensePrefixSum

import chisel3._
import chisel3.util._
import chisel3.experimental.{BaseModule, Direction, ChiselAnnotation, annotate}
import firrtl.annotations.{ModuleTarget, ReferenceTarget}


class ClockBridgeWithMuxTargetIO extends Bundle {
  val clockA = Output(Clock())
  val clockB = Output(Clock())
  val clockDynamic = Output(Clock())
  val sel    = Input(Bool())
}

class ClockBridgeWithMuxHostIO(protected val targetPortProto: ClockBridgeWithMuxTargetIO) extends Bundle with TimestampedHostPortIO {
  val clockA = OutputClockChannel(targetPortProto.clockA)
  val clockB = OutputClockChannel(targetPortProto.clockB)
  val clockDynamic = OutputClockChannel(targetPortProto.clockDynamic)
  val sel = InputChannel(targetPortProto.sel)
}

class ClockBridgeWithMux extends BlackBox with Bridge[ClockBridgeWithMuxHostIO, ClockBridgeWithMuxModule] with HasTimestampConstants {
  outer =>
  // Always generate the base (element 0 in our output vec)
  val io = IO(new ClockBridgeWithMuxTargetIO())
  val bridgeIO = new ClockBridgeWithMuxHostIO(io)
  val constructorArg = None
  generateAnnotations()
}

class ClockBridgeWithMuxModule()(implicit p: Parameters) extends BridgeModule[ClockBridgeWithMuxHostIO] {

  lazy val module = new BridgeModuleImp(this) {
    val io = IO(new WidgetIO())
    val hPort = IO(new ClockBridgeWithMuxHostIO(new ClockBridgeWithMuxTargetIO))
    val clockASource = TimestampedSource(Module(new ClockSource(1000)).clockOut)
    val clockBSource = TimestampedSource(Module(new ClockSource(2000)).clockOut)

    val Seq(clockAOut, clockAMuxIn) = FanOut(clockASource, 2)
    val Seq(clockBOut, clockBMuxIn) = FanOut(clockBSource, 2)

    val clockMux = Module(new TimestampedClockMux)
    clockMux.sel <> TimestampedSource(hPort.sel)
    clockMux.clockA <> clockAMuxIn
    clockMux.clockB <> clockBMuxIn
    hPort.clockA <> TimestampedSink(clockAOut)
    hPort.clockB <> TimestampedSink(clockBOut)
    hPort.clockDynamic <> TimestampedSink(clockMux.clockOut)

    val hCycleName = "hCycle"
    val hCycle = genWideRORegInit(0.U(64.W), hCycleName)
    hCycle := hCycle + 1.U

    // Count the number of clock tokens for which the fastest clock is scheduled to fire
    //  --> Use to calculate FMR
    val tCycleFastest = genWideRORegInit(0.U(64.W), "tCycle")
    genCRFile()
  }
}

