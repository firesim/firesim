// See LICENSE for license details.

package firesim.lib.bridges

import chisel3._
import chisel3.experimental.{annotate, ChiselAnnotation}

import firesim.lib.bridgeutils._

/** Parameters to construct a clock bridge from. Aggregates information about all the output clocks.
  *
  * @param clocks
  *   Clock information for each output clock.
  */
case class ClockParameters(clocks: Seq[RationalClock])

trait ClockBridgeConsts {
  val clockChannelName = "clocks"
}

/** Finds a virtual fast-clock whose period is the GCD of the periods of all requested clocks, and returns the period of
  * each requested clock as an integer multiple of that high-frequency virtual clock.
  */
object FindScaledPeriodGCD {
  def apply(phaseRelationships: Seq[(Int, Int)]): Seq[BigInt] = {
    val periodDivisors     = phaseRelationships.unzip._1
    val productOfDivisors  = periodDivisors.foldLeft(BigInt(1))(_ * _)
    val scaledMultipliers  = phaseRelationships.map({ case (divisor, multiplier) =>
      multiplier * productOfDivisors / divisor
    })
    val gcdOfScaledPeriods = scaledMultipliers.reduce((a, b) => a.gcd(b))
    val reducedPeriods     = scaledMultipliers.map(_ / gcdOfScaledPeriods)
    reducedPeriods
  }
}

/** The default target-side clock bridge. Generates a vector of clocks rationally related to one another. At least one
  * clock must have it's ratio set to one, this will be used as the base clock of the system. Global simulation times,
  * for features that might span multiple clock domains like printf synthesis, are expressed in terms of this base
  * clock.
  *
  * @param allClocks
  *   Rational clock information for each clock in the system.
  */
class RationalClockBridge(val allClocks: Seq[RationalClock]) extends BlackBox with ClockBridgeConsts {
  outer =>
  require(
    allClocks.exists(c => c.multiplier == c.divisor),
    "At least one requested clock must have multiplier / divisor == 1. This will be used as the base clock of the simulator.",
  )
  val io = IO(new Bundle {
    val clocks = Output(Vec(allClocks.size, Clock()))
  })

  val scaledPeriods = FindScaledPeriodGCD(allClocks.map { c => (c.multiplier, c.divisor) })
  val minPeriod     = scaledPeriods.min
  val clockMFMRs    = scaledPeriods.map { period => ((period + (minPeriod - 1)) / minPeriod).toInt }

  // Generate the bridge annotation
  annotate(new ChiselAnnotation {
    def toFirrtl =
      BridgeAnnotation(
        target               = outer.toTarget,
        bridgeChannels       = Seq(
          ClockBridgeChannel(name = clockChannelName, sinks = io.clocks.map(_.toTarget), clocks = allClocks, clockMFMRs)
        ),
        widgetClass          = "midas.widgets.ClockBridgeModule",
        widgetConstructorKey = Some(ClockParameters(allClocks)),
      )
  })
}

object RationalClockBridge {

  /** All additional provided clocks are relative to the an implicit base clock which is provided as the first index of
    * the clock vector.
    *
    * @param additionalClocks
    *   Specifications for additional clocks
    */
  def apply(additionalClocks: RationalClock*): RationalClockBridge =
    Module(new RationalClockBridge(RationalClock("BaseClock", 1, 1) +: additionalClocks))
}
