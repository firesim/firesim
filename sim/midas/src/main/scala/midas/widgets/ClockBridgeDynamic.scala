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

import scala.collection.immutable.ListMap

class DynamicClockTokenVector(protected val targetPortProto: ClockBridgeTargetIO) extends Record with TimestampedHostPortIO {
  val clocks = targetPortProto.clocks.map(OutputClockChannel(_))
  val elements = ListMap((clocks.zipWithIndex.map({ case (ch, idx) => s"clocks_$idx" -> ch })):_*)
  def cloneType() = new DynamicClockTokenVector(targetPortProto).asInstanceOf[this.type]
}

class DynamicClockBridge(additionalClocks: RationalClock*) extends BlackBox with
    Bridge[DynamicClockTokenVector, DynamicClockBridgeModule] with ClockBridgeConsts {
  outer =>
  // Always generate the base (element 0 in our output vec)
  val baseClock = RationalClock(refClockDomain, 1, 1)
  val allClocks = baseClock +: additionalClocks
  val io = IO(new ClockBridgeTargetIO(allClocks.length))
  val bridgeIO = new DynamicClockTokenVector(io)
  val constructorArg = Some(ClockBridgeCtorArgument(3000, allClocks))
  generateAnnotations()
}

class DynamicClockBridgeModule(arg: ClockBridgeCtorArgument)(implicit p: Parameters)
    extends BridgeModule[DynamicClockTokenVector] {
  lazy val module = new BridgeModuleImp(this) {
    val io = IO(new WidgetIO())
    val hPort = IO(new DynamicClockTokenVector(new ClockBridgeTargetIO(arg.clockInfo.size)))
    val phaseRelationships = arg.clockInfo map { cInfo => (cInfo.multiplier, cInfo.divisor) }

    val clockPeriodicity = FindScaledPeriodGCD(phaseRelationships)
    assert(arg.baseClockPeriodPS % clockPeriodicity.head == 0)
    val virtualClockPeriod = arg.baseClockPeriodPS / clockPeriodicity.head
    for ((clockChannel, multiple) <- hPort.clocks.zip(clockPeriodicity)) {
      val clockSource = Module(new ClockSource(virtualClockPeriod * multiple))
      clockChannel <> clockSource.clockOut
    }

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

    when (hPort.clocks(fastestClockIdx).fire && hPort.clocks(fastestClockIdx).bits.data) {
      tCycleFastest := tCycleFastest + 1.U
    }
    genCRFile()
  }
}
