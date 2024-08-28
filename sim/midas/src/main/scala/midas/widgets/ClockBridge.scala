// See LICENSE for license details.

package midas.widgets

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.util.DensePrefixSum

import midas.core.TargetChannelIO

import firesim.lib.bridges.{ClockBridgeConsts, ClockParameters, FindScaledPeriodGCD}
import firesim.lib.bridgeutils._

/** The host-land clock bridge interface. This consists of a single channel, carrying clock tokens. A clock token is a
  * Vec[Bool], one element per clock, When a bit is set, that clock domain will fire in the simulator time step that
  * consumes this clock token.
  *
  * NB: The target-time elapsed between tokens is not necessarily constant.
  *
  * @param numClocks
  *   The total number of clocks in the channel (inclusive of the base clock)
  */
class ClockTokenVector(numClocks: Int) extends Bundle with HasChannels with ClockBridgeConsts {
  val clocks = new DecoupledIO(Vec(numClocks, Bool()))

  def bridgeChannels() = Seq()

  def connectChannels2Port(bridgeAnno: BridgeIOAnnotation, targetIO: TargetChannelIO): Unit =
    targetIO.clockElement._2 <> clocks

  def generateAnnotations(): Unit = {}
}

/** The host-side implementation. Based on provided a clock information, generates a clock token stream which will be
  * sunk by the FAME-1 hub model. This token stream does not depend on the runtime-behavior of the target, allowing this
  * bridge run ahead of the rest of the simulation.
  *
  * Target and host time measurements provided by simif_t are facilitated with MMIO to this bridge
  *
  * @param params
  *   Structure describing the clocks of the clock bridge.
  */
class ClockBridgeModule(params: ClockParameters)(implicit p: Parameters) extends BridgeModule[ClockTokenVector] {
  val clockInfo   = params.clocks
  lazy val module = new BridgeModuleImp(this) {
    val io                 = IO(new WidgetIO())
    val hPort              = IO(new ClockTokenVector(clockInfo.size))
    val phaseRelationships = clockInfo.map { cInfo => (cInfo.multiplier, cInfo.divisor) }
    val clockTokenGen      = Module(new RationalClockTokenGenerator(phaseRelationships))
    hPort.clocks <> clockTokenGen.io

    val hCycleName = "hCycle"
    val hCycle     = genWideRORegInit(0.U(64.W), hCycleName)
    hCycle := hCycle + 1.U

    // Count the number of clock tokens for which the fastest clock is scheduled to fire
    //  --> Use to calculate FMR
    val tCycleFastest   = genWideRORegInit(0.U(64.W), "tCycle")
    val fastestClockIdx = (phaseRelationships)
      .map({ case (n, d) => n.toDouble / d })
      .zipWithIndex
      .sortBy(_._1)
      .last
      ._2

    when(hPort.clocks.fire && hPort.clocks.bits(fastestClockIdx)) {
      tCycleFastest := tCycleFastest + 1.U
    }
    genCRFile()

    override def genHeader(base: BigInt, memoryRegions: Map[String, BigInt], sb: StringBuilder): Unit = {
      genConstructor(base, sb, "clockmodule_t", "clock", Seq())
    }
  }

  /** Generates an infinite clock token stream based on rational relationship of each clock. To improve simulator FMR,
    * this module always produces non-zero clock tokens
    *
    * @param phaseRelationships
    *   multiplier, divisor pairs for each clock
    */
  class RationalClockTokenGenerator(phaseRelationships: Seq[(Int, Int)]) extends Module {
    val numClocks = phaseRelationships.size
    val io        = IO(new DecoupledIO(Vec(numClocks, Bool())))
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
    val timeToNextEdge = RegInit(VecInit(Seq.fill(numClocks)(0.U(counterWidth.W))))
    // Find the smallest number of virtual-clock cycles that must must advance
    // before one real clock would fire.
    val minStepsToEdge = DensePrefixSum(timeToNextEdge)({ case (a, b) => Mux(a < b, a, b) }).last

    // Advance the virtual clock (minStepsToEdge) cycles, and determine which
    // target clocks have an edge at that time to populate the clock token
    io.bits := VecInit(for ((reg, period) <- timeToNextEdge.zip(clockPeriodicity)) yield {
      val clockFiring = reg === minStepsToEdge
      when(io.ready) {
        reg := Mux(clockFiring, period.U, reg - minStepsToEdge)
      }
      clockFiring
    })
  }
}
