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
    out.latest.bits.data := valueOf(aSel) && !valueOf(bRegFeedback)
  }).out

  bReg.d <> (new CombLogic(Bool(), bSel, aRegFeedback) {
    out.latest.bits.data := !valueOf(bSel) && !valueOf(aRegFeedback)
  }).out

  clockOut <> (new CombLogic(Bool(), outClockA, outClockB, outAReg, outBReg){
    out.latest.bits.data := valueOf(outClockA) && valueOf(outAReg) || valueOf(outClockB) && valueOf(outBReg)
  }).out
}

