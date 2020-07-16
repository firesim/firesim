// See LICENSE for license details.

package midas.widgets

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.unittest.{UnitTest}


class ClockMux extends MultiIOModule {
  val clockA   = IO(Flipped(new TimestampedTuple(Bool())))
  val clockB   = IO(Flipped(new TimestampedTuple(Bool())))
  val sel      = IO(Flipped(new TimestampedTuple(Bool())))
  val clockOut = IO(new TimestampedTuple(Bool()))

  val aReg = Module(new TimestampedRegister(Bool(), Negedge, init = Some(false.B)))
  val bReg = Module(new TimestampedRegister(Bool(), Negedge, init = Some(false.B)))

  val Seq(outClockA, aRegClock) = FanOut(clockA, "outClockA", "aRegClock")
  val Seq(outClockB, bRegClock) = FanOut(clockB, "outClockB", "bRegClock")
  val Seq(outBReg, bRegFeedback) = FanOut(bReg.q, "outBReg", "bRegFeedback")
  val Seq(outAReg, aRegFeedback) = FanOut(aReg.q, "outAReg", "aRegFeedback")
  val Seq(aSel, bSel) = FanOut(sel, "aSel", "bSel")

  aReg.simClock <> aRegClock
  bReg.simClock <> bRegClock

  aReg.d <> (new CombLogic(Bool(), aSel, bRegFeedback) {
    out.latest.bits.data := !valueOf(aSel) && !valueOf(bRegFeedback)
  }).out

  bReg.d <> (new CombLogic(Bool(), bSel, aRegFeedback) {
    out.latest.bits.data := valueOf(bSel) && !valueOf(aRegFeedback)
  }).out

  clockOut <> (new CombLogic(Bool(), outClockA, outClockB, outAReg, outBReg){
    out.latest.bits.data := valueOf(outClockA) && valueOf(outAReg) || valueOf(outClockB) && valueOf(outBReg)
  }).out
}

class ReferenceClockMux extends BlackBox with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clockA = Input(Bool())
    val clockB = Input(Bool())
    val sel    = Input(Bool())
    val clockOut = Output(Bool())
  })
  addResource("/midas/widgets/ReferenceClockMux.sv")
}

object ClockMux {
  def instantiateAgainstReference(
    clockA: (Bool, TimestampedTuple[Bool]),
    clockB: (Bool, TimestampedTuple[Bool]),
    sel: (Bool, TimestampedTuple[Bool])): (Bool, TimestampedTuple[Bool]) = {

    val refClockMux = Module(new ReferenceClockMux)
    refClockMux.io.clockA := clockA._1
    refClockMux.io.clockB := clockB._1
    refClockMux.io.sel    := sel._1

    val modelClockMux = Module(new ClockMux)
    modelClockMux.clockA <> clockA._2
    modelClockMux.clockB <> clockB._2
    modelClockMux.sel    <> sel._2
    (refClockMux.io.clockOut, modelClockMux.clockOut)
  }
}


class TimestampedClockMuxTest(
    clockAPeriodPS: Int,
    clockBPeriodPS: Int,
    selPeriodPS: Int,
    timeout: Int = 50000)(implicit p: Parameters) extends UnitTest(timeout) {

  val clockATuple = ClockSource.instantiateAgainstReference(clockAPeriodPS)
  val clockBTuple = ClockSource.instantiateAgainstReference(clockBPeriodPS)
  val selTuple    = ClockSource.instantiateAgainstReference(selPeriodPS)
  val (reference, model) = ClockMux.instantiateAgainstReference(clockATuple, clockBTuple, selTuple)
  io.finished := TimestampedTokenTraceEquivalence(reference, model, timeout)
}
