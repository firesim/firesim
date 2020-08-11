// See LICENSE for license details.

package midas.widgets

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.unittest.{UnitTest}

/**
  * NB: The RC clock dividers are non-deterministic; their initial output value
  * can differ across simulations, causing these tests to fail randomly.
  * However, given the same initial values these models match the RC sources.
  */

class ClockDivider2 extends MultiIOModule {
  val clk_in   = IO(Flipped(new TimestampedTuple(Bool())))
  val clk_out  = IO(new TimestampedTuple(Bool()))
  val reg = Module(new TimestampedRegister(Bool(), Posedge, init = Some(false.B)))
  reg.simClock <> clk_in
  val Seq(feedback, out) = FanOut(reg.q, "feedback", "out")
  clk_out <> out
  reg.d <> (new CombLogic(Bool(), feedback){
    out.latest.bits.data := ~valueOf(feedback)
  }).out
}

object ClockDivider2 {
  def instantiateAgainstReference(clockIn: (Bool, TimestampedTuple[Bool])): (Bool, TimestampedTuple[Bool]) = {

    val refClockDiv = Module(new freechips.rocketchip.util.ClockDivider2)
    refClockDiv.io.clk_in := clockIn._1.asClock

    val modelClockDiv = Module(new ClockDivider2)
    modelClockDiv.clk_in <> clockIn._2
    (refClockDiv.io.clk_out.asBool, modelClockDiv.clk_out)
  }
}

class RocketChipClockDivider2Test(
    clockPeriodPS: Int,
    timeout: Int = 50000)(implicit p: Parameters) extends UnitTest(timeout) {

  val clockTuple = ClockSource.instantiateAgainstReference(clockPeriodPS)
  val (reference, model) = ClockDivider2.instantiateAgainstReference(clockTuple)
  io.finished := TimestampedTokenTraceEquivalence(reference, model, timeout)
}

class ClockDivider3 extends MultiIOModule {
  val clk_in   = IO(Flipped(new TimestampedTuple(Bool())))
  val clk_out  = IO(new TimestampedTuple(Bool()))
  // The init value of the register in the RC reference can come up as 0 or 1
  val reg = Module(new TimestampedRegister(Bool(), Posedge, init = Some(true.B)))
  val delay = Module(new TimestampedRegister(Bool(), Posedge, init = Some(false.B)))

  val Seq(regFeedback, reg2Delay, out) = FanOut(reg.q, "regFeedback", "reg2Delay", "out")
  val Seq(delayFeedback, delay2Reg) = FanOut(delay.q, "delayFeedback", "delay2Reg")
  val Seq(regClock, delayClock) = FanOut(clk_in, "regClock", "delayClock")

  reg.simClock <> regClock
  reg.d <> (new CombLogic(Bool(), regFeedback, delay2Reg){
    out.latest.bits.data := Mux(
      valueOf(regFeedback),
      Mux(valueOf(delay2Reg), false.B, valueOf(regFeedback)),
      false.B)
  }).out

  delay.simClock <> delayClock
  delay.d <> (new CombLogic(Bool(), delayFeedback, reg2Delay){
    out.latest.bits.data := Mux(valueOf(reg2Delay), ~valueOf(delayFeedback), false.B)
  }).out

  clk_out <> out
}

object ClockDivider3 {
  def instantiateAgainstReference(clockIn: (Bool, TimestampedTuple[Bool])): (Bool, TimestampedTuple[Bool]) = {

    val refClockDiv = Module(new freechips.rocketchip.util.ClockDivider3)
    refClockDiv.io.clk_in := clockIn._1.asClock

    val modelClockDiv = Module(new ClockDivider3)
    modelClockDiv.clk_in <> clockIn._2
    (refClockDiv.io.clk_out.asBool, modelClockDiv.clk_out)
  }
}

class RocketChipClockDivider3Test(
    clockPeriodPS: Int,
    timeout: Int = 50000)(implicit p: Parameters) extends UnitTest(timeout) {

  val clockTuple = ClockSource.instantiateAgainstReference(clockPeriodPS, initValue = true)
  val (reference, model) = ClockDivider3.instantiateAgainstReference(clockTuple)
  io.finished := TimestampedTokenTraceEquivalence(reference, model, timeout)
}
