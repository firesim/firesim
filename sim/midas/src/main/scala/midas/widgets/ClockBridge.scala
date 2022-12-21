// See LICENSE for license details.

package midas.widgets

import midas.core.{TargetChannelIO, SimUtils}
import midas.core.SimUtils.{RVChTuple}
import midas.passes.fame.{FAMEChannelConnectionAnnotation, TargetClockChannel, RTRenamer}

import freechips.rocketchip.config.Parameters
import freechips.rocketchip.util.DensePrefixSum

import chisel3._
import chisel3.util._
import chisel3.experimental.{BaseModule, Direction, ChiselAnnotation, annotate}

import firrtl.{RenameMap}
import firrtl.annotations.{Annotation, ModuleTarget, ReferenceTarget}

/**
  * Defines a generated clock as a rational multiple of some reference clock. The generated
  * clock has a frequency (multiplier / divisor) times that of reference.
  *
  * @param name An identifier for the associated clock domain
  *
  * @param multiplier See class comment.
  *
  * @param divisor See class comment.
  */
case class RationalClock(name: String, multiplier: Int, divisor: Int) {
  def simplify: RationalClock = {
    val gcd = BigInt(multiplier).gcd(BigInt(divisor)).intValue
    RationalClock(name, multiplier / gcd, divisor / gcd)
  }

  def equalFrequency(that: RationalClock): Boolean =
    this.simplify.multiplier == that.simplify.multiplier &&
    this.simplify.divisor == that.simplify.divisor
}

sealed trait ClockBridgeConsts {
  val clockChannelName = "clocks"
}

/**
  * Finds a virtual fast-clock whose period is the GCD of the periods of all requested
  * clocks, and returns the period of each requested clock as an integer multiple of that
  * high-frequency virtual clock.
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

/**
 * Parameters to construct a clock bridge from. Aggregates information about all the output clocks.
 *
 * @param clocks Clock information for each output clock.
 */
case class ClockParameters(clocks: Seq[RationalClock])

/**
  * The default target-side clock bridge. Generates a vector of
  * clocks rationally related to one another. At least one clock must have it's ratio set to one, this will be used
  * as the base clock of the system. Global simulation times, for features that might span multiple clock domains
  * like printf synthesis, are expressed in terms of this base clock.
  *
  * @param allClocks Rational clock information for each clock in the system.
  *
  */
class RationalClockBridge(val allClocks: Seq[RationalClock]) extends BlackBox with ClockBridgeConsts {
  outer =>
  require(allClocks.exists(c => c.multiplier == c.divisor),
    s"At least one requested clock must have multiplier / divisor == 1. This will be used as the base clock of the simulator.")
  val io = IO(new Bundle {
    val clocks = Output(Vec(allClocks.size, Clock()))
  })

  val scaledPeriods = FindScaledPeriodGCD(allClocks.map { c => (c.multiplier, c.divisor) })
  val minPeriod = scaledPeriods.min
  val clockMFMRs = scaledPeriods.map { period => ((period + (minPeriod - 1)) / minPeriod).toInt }

  // Generate the bridge annotation
  annotate(new ChiselAnnotation { def toFirrtl =
      BridgeAnnotation(
        target = outer.toTarget,
        bridgeChannels = Seq(
          ClockBridgeChannel(
            name = clockChannelName,
            sinks = io.clocks.map(_.toTarget),
            clocks = allClocks,
            clockMFMRs)),
        widgetClass = classOf[ClockBridgeModule].getName,
        widgetConstructorKey = Some(ClockParameters(allClocks))
      )
  })
}

object RationalClockBridge {
  /**
    * All additional provided clocks are relative to the an implicit base clock
    * which is provided as the first index of the clock vector.
    *
    * @param additionalClocks Specifications for additional clocks
    */
  def apply(additionalClocks: RationalClock*): RationalClockBridge =
    Module(new RationalClockBridge(RationalClock("BaseClock", 1, 1) +: additionalClocks))
}

/**
  * The host-land clock bridge interface. This consists of a single channel,
  * carrying clock tokens. A clock token is a Vec[Bool], one element per clock, When a bit is set,
  * that clock domain will fire in the simulator time step that consumes this clock token.
  *
  * NB: The target-time elapsed between tokens is not necessarily constant.
  *
  * @param numClocks The total number of clocks in the channel (inclusive of the base clock)
  *
  */
class ClockTokenVector(numClocks: Int) extends Bundle with HasChannels with ClockBridgeConsts {
  val clocks = new DecoupledIO(Vec(numClocks, Bool()))

  def bridgeChannels = Seq()

  def tokenHashers() = {
    println("CALLED tokenHashers from ClockTokenVector")
    Unit
  }

  def getOutputChannelPorts() = Seq()
  def getInputChannelPorts() = Seq()

  def connectChannels2Port(bridgeAnno: BridgeIOAnnotation, targetIO: TargetChannelIO): Unit =
    targetIO.clockElement._2 <> clocks

  def generateAnnotations(): Unit = {}
}

/**
  * The host-side implementation. Based on provided a clock information, generates a clock token stream
  * which will be sunk by the FAME-1 hub model. This token stream does not
  * depend on the runtime-behavior of the target, allowing this bridge run
  * ahead of the rest of the simulation.
  *
  * Target and host time measurements provided by simif_t are facilitated with MMIO to this bridge
  *
  * @param params Structure describing the clocks of the clock bridge.
  *
  */
class ClockBridgeModule(params: ClockParameters)(implicit p: Parameters)
    extends BridgeModule[ClockTokenVector] {
  val clockInfo = params.clocks
  lazy val module = new BridgeModuleImp(this) {
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
  * Generates an infinite clock token stream based on rational relationship of each clock.
  * To improve simulator FMR, this module always produces non-zero clock tokens
  *
  * @param phaseRelationships multiplier, divisor pairs for each clock
  */
class RationalClockTokenGenerator(phaseRelationships: Seq[(Int, Int)]) extends Module {
  val numClocks = phaseRelationships.size
  val io = IO(new DecoupledIO(Vec(numClocks, Bool())))
  // The clock token stream is known a priori!
  io.valid := true.B

  // Determine the number of virtual-clock cycles for each target clock.
  val clockPeriodicity = FindScaledPeriodGCD(phaseRelationships)
  val counterWidth     = clockPeriodicity.map(p => log2Ceil(p + 1)).reduce((a, b) => math.max(a, b))

  // This is an arbitrarily selected number; feel free to increase it. If we
  // need more time resolution we can trivially pipeline this thing.
  val maxCounterWidth = 16
  require(counterWidth <= maxCounterWidth, "Ensure this circuit doesn't blow up")

  // For each target clock, count the number of virtual cycles until the next expected clock edge
  val timeToNextEdge   = RegInit(VecInit(Seq.fill(numClocks)(0.U(counterWidth.W))))
  // Find the smallest number of virtual-clock cycles that must must advance
  // before one real clock would fire.
  val minStepsToEdge   = DensePrefixSum(timeToNextEdge)({ case (a, b) => Mux(a < b, a, b) }).last

  // Advance the virtual clock (minStepsToEdge) cycles, and determine which
  // target clocks have an edge at that time to populate the clock token
  io.bits := VecInit(for ((reg, period) <- timeToNextEdge.zip(clockPeriodicity)) yield {
    val clockFiring = reg === minStepsToEdge
    when (io.ready) {
      reg := Mux(clockFiring, period.U, reg - minStepsToEdge)
    }
    clockFiring
  })
  }
}
