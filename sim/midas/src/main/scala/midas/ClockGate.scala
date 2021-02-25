// See LICENSE for license details.

package midas.widgets

import chisel3._
import chisel3.util._
import chisel3.experimental.BaseModule

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.unittest.{UnitTest}

// Note: We use a negedge triggered flip-flop instead of a latch here.
class ClockGateCore extends MultiIOModule with HasTimestampConstants {
  val clockIn   = IO(Flipped(new TimestampedTuple(Bool())))
  val en        = IO(Flipped(new TimestampedTuple(Bool())))
  val clockOut  = IO(new TimestampedTuple(Bool()))

  val Seq(regClock, outClock) = FanOut(clockIn, "regClock", "outClock")

  val reg = Module(new TimestampedRegister(Bool(), Negedge, init = Some(true.B)))
  reg.d <> en
  reg.simClock <> regClock
  val regQ = reg.q
  clockOut <> (new CombLogic(Bool(), regQ, outClock) {
    out.latest.bits.data := valueOf(regQ) && valueOf(outClock)
  }).out

  class Reference extends RawModule {
    val clockIn   = IO(Input(Bool()))
    val en        = IO(Input(Bool()))
    val clockOut  = IO(Output(Bool()))
    val reg = Module(new ReferenceRegister(Bool(), Negedge, init = Some(true.B)))
    reg.reset := false.B
    reg.clock := clockIn
    reg.d := en
    clockOut := reg.q && clockIn
  }
}

object ClockGateCore {
  def instantiateAgainstReference(
    clockIn: (Bool, TimestampedTuple[Bool]),
    en: (Bool, TimestampedTuple[Bool])): (Bool, TimestampedTuple[Bool]) = {

    val modelClockGate = Module(new ClockGateCore)
    modelClockGate.clockIn <> clockIn._2
    modelClockGate.en    <> en._2

    val refClockGate = Module(new modelClockGate.Reference)
    refClockGate.clockIn := clockIn._1
    refClockGate.en    := en._1
    (refClockGate.clockOut, modelClockGate.clockOut)
  }
}

class ClockGateTest(
    inputPeriodPS: Int,
    enPeriodPS: Int,
    timeout: Int = 50000)(implicit p: Parameters) extends UnitTest(timeout) {

  val clockIn = ClockSource.instantiateAgainstReference(inputPeriodPS)
  val enTuple = ClockSource.instantiateAgainstReference(enPeriodPS)
  val (reference, model) = ClockGateCore.instantiateAgainstReference(clockIn, enTuple)
  io.finished := TimestampedTokenTraceEquivalence(reference, model, timeout)
}

class ClockGateHostIO(
    private val tClockIn: Clock = Clock(),
    private val tClockOut: Clock = Clock(),
    private val tEn: Bool = Bool()) extends Bundle with TimestampedHostPortIO {
  val clockIn = InputClockChannel(tClockIn)
  val clockOut = OutputClockChannel(tClockOut)
  val en = InputChannel(tEn)
}

private[midas] class BridgeableClockGate (
    mod: BaseModule,
    clockIn: Clock,
    clockOut: Clock,
    en: Bool) extends Bridgeable[ClockGateHostIO, ClockGateBridgeModule] {
  def target = mod.toNamed.toTarget
  def bridgeIO = new ClockGateHostIO(clockIn, clockOut, en)
  def constructorArg = None
}

object BridgeableClockGate {
  def apply(mod: BaseModule, clockIn: Clock, clockOut: Clock, en: Bool): Unit = {
    val annotator = new BridgeableClockGate(mod, clockIn, clockOut, en)
    annotator.generateAnnotations()
  }
}

class ClockGateBridgeModule()(implicit p: Parameters) extends BridgeModule[ClockGateHostIO] {
  lazy val module = new BridgeModuleImp(this) {
    val io = IO(new WidgetIO())
    val hPort = IO(new ClockGateHostIO)
    val clockGate = Module(new ClockGateCore)
    // Unpack the tokens
    clockGate.en <> TimestampedSource(hPort.en)
    clockGate.clockIn <> TimestampedSource(hPort.clockIn)
    hPort.clockOut <> TimestampedSink(clockGate.clockOut)
  }
}
