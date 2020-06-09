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
  val clocks = Output(Vec(3, Clock()))
  val sel    = Input(Bool())
}

class ClockMuxTargetIO extends Bundle {
  val out = Output(Clock())
  val inA = Input(Clock())
  val inB = Input(Clock())
  val sel = Input(Bool())
}

class ClockMuxChannelizedIO(protected val targetPortProto: ClockMuxTargetIO) extends Bundle with TimestampedHostPortIO {
  val out = OutputClockChannel(targetPortProto.out)
  val inA = InputClockChannel(targetPortProto.inA)
  val inB = InputClockChannel(targetPortProto.inB)
  val sel = InputChannel(targetPortProto.sel)
}

class ClockMuxBridge extends Module with  HasTimestampConstants {
  val io = IO(new ClockMuxChannelizedIO(new ClockMuxTargetIO))
}

class ClockBridgeWithMuxHostIO(protected val targetPortProto: ClockBridgeWithMuxTargetIO) extends Bundle with TimestampedHostPortIO {
  val clocks      = OutputClockVecChannel(targetPortProto.clocks)
  val clockSelect = InputChannel(targetPortProto.sel)
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
    val phaseRelationships = Seq((1,1), (1,2))
    val clockTokenGen = Module(new RationalClockTokenGenerator(1000, phaseRelationships))
    hPort.clocks.bits.data(0) <> clockTokenGen.io.bits.data(0)
    hPort.clocks.bits.data(1) <> clockTokenGen.io.bits.data(1)
    hPort.clocks.bits.time <> clockTokenGen.io.bits.time

    val hCycleName = "hCycle"
    val hCycle = genWideRORegInit(0.U(64.W), hCycleName)
    hCycle := hCycle + 1.U

    // Count the number of clock tokens for which the fastest clock is scheduled to fire
    //  --> Use to calculate FMR
    val tCycleFastest = genWideRORegInit(0.U(64.W), "tCycle")
    val fastestClockIdx = (phaseRelationships).map({ case (n, d) => n.toDouble / d })
                                              .zipWithIndex
                                              .sortBy(_._1)
                                              .last._2

    when (hPort.clocks.fire) {
      tCycleFastest := tCycleFastest + 1.U
    }
    genCRFile()
  }
}

