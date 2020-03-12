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

case class RationalClock(name: String, multiplier: Int, divisor: Int)

sealed trait ClockBridgeConsts {
  val clockChannelName = "clocks"
  val refClockDomain = "baseClock"
}

case class ClockBridgeAnnotation(val target: ModuleTarget, clocks: Seq[RationalClock])
    extends BridgeAnnotation with ClockBridgeConsts {
  val channelNames = Seq(clockChannelName)
  def duplicate(n: ModuleTarget) = this.copy(target)
  def toIOAnnotation(port: String): BridgeIOAnnotation = {
    val channelMapping = channelNames.map(oldName => oldName -> s"${port}_$oldName")
    BridgeIOAnnotation(
      target.copy(module = target.circuit).ref(port),
      channelMapping.toMap,
      Some((p: Parameters) => new ClockBridgeModule(clocks)(p))
    )
  }
}

class RationalClockBridge(additionalClocks: RationalClock*) extends BlackBox with ClockBridgeConsts {
  outer =>
  val baseClock = RationalClock(refClockDomain, 1, 1)
  val allClocks = baseClock +: additionalClocks
  val io = IO(new Bundle {
    val clocks = Output(Vec(allClocks.size, Clock()))
  })

  // Generate the bridge annotation
  annotate(new ChiselAnnotation { def toFirrtl = ClockBridgeAnnotation( outer.toTarget, allClocks) })
  annotate(new ChiselAnnotation { def toFirrtl =
      FAMEChannelConnectionAnnotation(
        clockChannelName,
        channelInfo = TargetClockChannel,
        clock = None, // Clock channels do not have a reference clock
        sinks = Some(io.clocks.map(_.toTarget)),
        sources = None
      )
  })
}

class ClockTokenVector(numClocks: Int) extends TokenizedRecord with ClockBridgeConsts {
  def targetPortProto(): Vec[Bool] = Vec(numClocks, Bool())
  val clocks = new DecoupledIO(targetPortProto)

  def outputWireChannels = Seq(clocks -> clockChannelName)
  def inputWireChannels = Seq()
  def outputRVChannels = Seq()
  def inputRVChannels = Seq()

  def connectChannels2Port(bridgeAnno: BridgeIOAnnotation, simIo: SimWrapperChannels): Unit = {
    val local2globalName = bridgeAnno.channelMapping.toMap
    for (localName <- outputChannelNames) {
      simIo.clockElement._2 <> elements(localName)
    }
  }

  val elements = collection.immutable.ListMap(clockChannelName -> clocks)
  override def cloneType(): this.type = new ClockTokenVector(numClocks).asInstanceOf[this.type]
  def generateAnnotations(): Unit = {}
}

class ClockBridgeModule(clockInfo: Seq[RationalClock])(implicit p: Parameters)
    extends BridgeModule[ClockTokenVector] {
  val io = IO(new WidgetIO())
  val hPort = IO(new ClockTokenVector(clockInfo.size))
  val phaseRelationships = clockInfo map { cInfo => (cInfo.multiplier, cInfo.divisor) }
  val clockTokenGen = Module(new RationalClockTokenGenerator(phaseRelationships))
  hPort.clocks <> clockTokenGen.io

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

  when (hPort.clocks.fire && hPort.clocks.bits(fastestClockIdx)) {
    tCycleFastest := tCycleFastest + 1.U
  }
  genCRFile()
}

/**
  * Finds a clock whose period is the GCD of the periods of all requested
  * clocks, and returns the period of each requested clock as a multiple of that
  * high-frequency base clock
  */

object FindScaledPeriodGCD {
  def apply(phaseRelationships: Seq[(Int, Int)]): Seq[BigInt] = {
    val periodDivisors = phaseRelationships.unzip._1
    val productOfDivisors  = periodDivisors.foldLeft(BigInt(1))(_ * _)
    val scaledMultipliers  = phaseRelationships.map({ case (divisor, multiplier) =>  multiplier * productOfDivisors / divisor })
    val gcdOfScaledPeriods = scaledMultipliers.reduce((a, b) => a.gcd(b))
    val reducedPeriods     = scaledMultipliers.map(_ / gcdOfScaledPeriods)
    reducedPeriods
  }
}

class RationalClockTokenGenerator(phaseRelationships: Seq[(Int, Int)]) extends Module {
  val numClocks = phaseRelationships.size
  val io = IO(new DecoupledIO(Vec(numClocks, Bool())))
  io.valid := true.B

  val clockPeriodicity = FindScaledPeriodGCD(phaseRelationships)
  val counterWidth     = clockPeriodicity.map(p => log2Ceil(p + 1)).reduce((a, b) => math.max(a, b))

  // This is an arbitrarily selected number; feel free to increase it
  val maxCounterWidth = 16
  require(counterWidth <= maxCounterWidth, "Ensure this circuit doesn't blow up")

  val timeToNextEdge   = RegInit(VecInit(Seq.fill(numClocks)(0.U(counterWidth.W))))
  val minStepsToEdge   = DensePrefixSum(timeToNextEdge)({ case (a, b) => Mux(a < b, a, b) }).last

  io.bits := VecInit(for ((reg, period) <- timeToNextEdge.zip(clockPeriodicity)) yield {
    val clockFiring = reg === minStepsToEdge
    when (io.ready) {
      reg := Mux(clockFiring, period.U, reg - minStepsToEdge)
    }
    clockFiring
  })
}
