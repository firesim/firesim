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

trait ClockBridgeConsts {
  val clockChannelName = "clocks"
}

/**
  * The host-land clock bridge interface. This consists of a single channel,
  * carrying clock tokens. A clock token is a Vec[Bool], one element per clock, When a bit is set,
  * that clock domain will fire in the simulator time step that consumes this clock token.
  *
  * NB: The target-time elapsed between tokens is not necessarily constant.
  *
  */

class ClockTokenVector(protected val targetPortProto: ClockBridgeTargetIO) extends Record with TimestampedHostPortIO {
  val clocks = targetPortProto.clocks.map(OutputClockChannel(_))
  val elements = ListMap((clocks.zipWithIndex.map({ case (ch, idx) => s"clocks_$idx" -> ch })):_*)
  def cloneType() = new ClockTokenVector(targetPortProto).asInstanceOf[this.type]
}

class ClockBridgeTargetIO(numClocks: Int) extends Bundle {
  val clocks = Output(Vec(numClocks, Clock()))
}

case class ClockBridgeCtorArgument(baseClockPeriodPS: BigInt, clockInfo: Seq[RationalClock])

/**
  * The default target-side clock bridge. Generates a "base clock" and a vector of
  * additional clocks rationally related to that base clock. Simulation times are
  * generally expressed in terms of this base clock.
  *
  * @param allClocks Rational clock information for each clock in the system.
  *
  */
class RationalClockBridge(val allClocks: Seq[RationalClock]) extends BlackBox with  
    Bridge[ClockTokenVector, ClockBridgeModule] with ClockBridgeConsts {
  outer =>
  require(allClocks.exists(c => c.multiplier == c.divisor),
    s"At least one requested clock must have multiplier / divisor == 1. This will be used as the base clock of the simulator.")
  val io = IO(new ClockBridgeTargetIO(allClocks.length))
  val bridgeIO = new ClockTokenVector(io)
  val constructorArg = Some(ClockBridgeCtorArgument(1000, allClocks))
  generateAnnotations()
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
  * The host-side implementation. Based on provided a clock information, generates a clock token stream
  * which will be sunk by the FAME-1 hub model. This token stream does not
  * depend on the runtime-behavior of the target, allowing this bridge run
  * ahead of the rest of the simulation.
  *
  * Target and host time measurements provided by simif_t are facilitated with MMIO to this bridge
  *
  * @param arg Serialized constructor argument
  *
  */
class ClockBridgeModule(arg: ClockBridgeCtorArgument)(implicit p: Parameters)
    extends BridgeModule[ClockTokenVector] {
  lazy val module = new BridgeModuleImp(this) {
    val io = IO(new WidgetIO())
    val hPort = IO(new ClockTokenVector(new ClockBridgeTargetIO(arg.clockInfo.size)))
    val phaseRelationships = arg.clockInfo map { cInfo => (cInfo.multiplier, cInfo.divisor) }
    val clockPeriodicity = FindScaledPeriodGCD(phaseRelationships)
    assert(arg.baseClockPeriodPS % clockPeriodicity.head == 0)
    val virtualClockPeriod = arg.baseClockPeriodPS / clockPeriodicity.head
    for ((clockChannel, multiple) <- hPort.clocks.zip(clockPeriodicity)) {
      val clockSource = Module(new ClockSource(ClockSourceParams(virtualClockPeriod * multiple)))
      clockChannel <> clockSource.clockOut
    }
  }
}

/**
  * Finds a virtual fast clock whose period is the GCD of the periods of all requested
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
  * Generates an infinite clock token stream based on rational relationship of each clock.
  * To improve simulator FMR, this module always produces non-zero clock tokens
  *
  * @param phaseRelationships multiplier, divisor pairs for each clock
  */
class RationalClockTokenGenerator(baseClockPeriodPS: BigInt, phaseRelationships: Seq[(Int, Int)]) extends Module with HasTimestampConstants {
  val numClocks = phaseRelationships.size
  val io = IO(new DecoupledIO(new TimestampedToken(Vec(numClocks, Bool()))))
  // The clock token stream is known a priori!
  io.valid := true.B

  // Determine the number of virtual-clock cycles for each target clock.
  val clockPeriodicity = FindScaledPeriodGCD(phaseRelationships)
  assert(baseClockPeriodPS % clockPeriodicity.head == 0)
  val virtualClockPeriod = baseClockPeriodPS / clockPeriodicity.head

  val counterWidth     = clockPeriodicity.map(p => log2Ceil(p + 1)).reduce((a, b) => math.max(a, b))

  // This is an arbitrarily selected number; feel free to increase it. If we
  // need more time resolution we can trivially pipeline this thing.
  val maxCounterWidth = 16
  require(counterWidth <= maxCounterWidth, "Ensure this circuit doesn't blow up")

  val simulationTime = RegInit(0.U(timestampWidth.W))
  // For each target clock, count the number of virtual cycles until the next expected clock edge
  val timeToNextEdge   = RegInit(VecInit(Seq.fill(numClocks)(0.U(counterWidth.W))))
  // Find the smallest number of virtual-clock cycles that must must advance
  // before one real clock would fire.
  val minStepsToEdge   = DensePrefixSum(timeToNextEdge)({ case (a, b) => Mux(a < b, a, b) }).last
  io.bits.time := simulationTime + virtualClockPeriod.U * minStepsToEdge

  // Advance the virtual clock (minStepsToEdge) cycles, and determine which
  // target clocks have an edge at that time to populate the clock token
  io.bits.data := VecInit(for ((reg, period) <- timeToNextEdge.zip(clockPeriodicity)) yield {
    val clockFiring = reg === minStepsToEdge
    when (io.ready) {
      reg := Mux(clockFiring, period.U, reg - minStepsToEdge)
    }
    clockFiring
  })
  when(io.ready) {
    simulationTime := io.bits.time
  }
}
