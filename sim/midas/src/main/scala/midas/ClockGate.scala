// See LICENSE for license details.

package midas.widgets

import chisel3._
import chisel3.util._
import chisel3.experimental.BaseModule

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.unittest.{UnitTest}

// Note: We use a negedge triggered flip-flop instead of a latch here.
/**
  * The timing model for a clock gating circuit
  *
  * @param enableSynchronized This is a control lookahead optimization which
  * can be set when en is known to transition only on clockIn positive edges.
  * In other words, it is driven logic synchronous with the input clock.
  */
class ClockGateCore(enableSynchronized: Boolean = true) extends MultiIOModule with HasTimestampConstants {
  val clockIn   = IO(Flipped(new TimestampedTuple(Bool())))
  val en        = IO(Flipped(new TimestampedTuple(Bool())))
  val clockOut  = IO(new TimestampedTuple(Bool()))

  if (enableSynchronized) {
    val outOld = RegInit({
      val w = Wire(ValidIO(new TimestampedToken(Bool())))
      w := DontCare
      w.valid := false.B
      w
    })

    clockOut.old := outOld
    when(clockOut.fire) {
      outOld := clockOut.latest
    }

    val enReg = RegInit(true.B)
    val lastPosedgeTime = RegInit(0.U(timestampWidth.W))
    val posedge = clockIn.latest.bits.data && !clockIn.old.bits.data && clockIn.old.valid

    clockOut.latest.valid := clockIn.latest.valid && en.old.valid && (!posedge || en.definedAt(lastPosedgeTime))
    clockOut.latest.bits.time := clockIn.latest.bits.time
    clockOut.latest.bits.data := enReg && clockIn.latest.bits.data

    clockIn.observed := clockIn.unchanged ||
      clockOut.observed && (!posedge || en.definedAt(lastPosedgeTime) || !clockIn.old.valid)
    // Since EN is synchronous with the input clock, it can never be defined
    // ahead of clockIn. The most recent non-null message we could have
    // received would have been launched by the last positive edge we processed.
    // Thus, so long as en is defined up until the time of that last positive,
    // we can determine whether to enable clock, without waiting further.
    //
    // Since we only need the most up to date value, we need never stall here.
    en.observed := !en.old.valid || clockIn.definedUntil > en.latest.bits.time

    // To determined whether to let an edge pass, we look to see whether we have 
    // a value for the en at the last positive edge. 
    when(clockIn.latest.valid && posedge && en.old.valid && en.definedAt(lastPosedgeTime)) {
      val enable = en.valueAt(lastPosedgeTime)
      clockOut.latest.bits.data := enable
      when (clockOut.observed) {
        enReg := enable
        lastPosedgeTime := clockOut.latest.bits.time
      }
    }
  } else {
    val Seq(regClock, outClock) = FanOut(clockIn, "regClock", "outClock")

    val reg = Module(new TimestampedRegister(Bool(), Negedge, init = Some(true.B)))
    reg.d <> en
    reg.simClock <> regClock
    val regQ = reg.q
    clockOut <> (new CombLogic(Bool(), regQ, outClock) {
      out.latest.bits.data := valueOf(regQ) && valueOf(outClock)
    }).out
  }

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

  val clockIn = ClockSource.instantiateAgainstReference(inputPeriodPS, initValue = true)
  val enTuple = ClockSource.instantiateAgainstReference(enPeriodPS, initValue = true)
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
