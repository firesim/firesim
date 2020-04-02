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
case class RationalClock(name: String, multiplier: Int, divisor: Int)

sealed trait ClockBridgeConsts {
  val clockChannelName = "clocks"
  val refClockDomain = "baseClock"
}

/**
  * A custom bridge annotation for the Clock Bridge. Unique so that we can
  * trivially match against it in bridge extraction.
  *
  * @param target The target-side module for the CB
  *
  * @param clocks The associated clock information for each output clock (including the base).
  */

case class ClockBridgeAnnotation(val target: ModuleTarget, clocks: Seq[RationalClock])
    extends BridgeAnnotation with ClockBridgeConsts {
  val channelNames = Seq(clockChannelName)
  def duplicate(n: ModuleTarget) = this.copy(target)
  def toIOAnnotation(port: String): BridgeIOAnnotation = {
    val channelMapping = channelNames.map(oldName => oldName -> s"${port}_$oldName")
    BridgeIOAnnotation(
      target.copy(module = target.circuit).ref(port),
      channelMapping.toMap,
      widget = Some((p: Parameters) => new ClockBridgeModule(clocks)(p))
    )
  }
}

/**
  * The default target-side clock bridge. Generates a "base clock" and a vector of
  * additional clocks related to that base clock. Simulation times are
  * generally expressed in terms of this base clock.
  *
  * @param additionalClocks Rational clock information for each additional
  * clock beyond the base
  */
class RationalClockBridge(additionalClocks: RationalClock*) extends BlackBox with ClockBridgeConsts {
  outer =>
  // Always generate the base (element 0 in our output vec)
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
        channelInfo = TargetClockChannel(allClocks),
        clock = None, // Clock channels do not have a reference clock
        sinks = Some(io.clocks.map(_.toTarget)),
        sources = None
      )
  })
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

/**
  * The host-side implementation. Based on provided a clock information, generates a clock token stream
  * which will be sunk by the FAME-1 hub model. This token stream does not
  * depend on the runtime-behavior of the target, allowing this bridge run
  * ahead of the rest of the simulation.
  *
  * Target and host time measurements provided by simif_t are facilitated with MMIO to this bridge
  *
  * @param clockInfo Clock frequency information for each target clock
  *
  */
class ClockBridgeModule(clockInfo: Seq[RationalClock])(implicit p: Parameters)
    extends BridgeModule[ClockTokenVector] {
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
