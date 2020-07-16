// See LICENSE for license details.

package midas.widgets

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.unittest.{UnitTest}

abstract class CombLogic[T <: Data](gen: =>T, sensitivityList: TimestampedTuple[_ <: Data]*) {
  sensitivityList foreach dontTouch.apply
  val sensitivities = sensitivityList.toSet
  val minTime = sensitivityList.map(_.definedUntil()).reduce((a, b) => Mux(a < b, a, b))
  minTime.suggestName("minTime")
  val old = RegInit({
    val w = Wire(ValidIO(new TimestampedToken(gen)))
    w := DontCare
    w.valid := false.B
    w
  })
  val out = Wire(new TimestampedTuple(gen))
  out.old := old

  val allInputsDefined = sensitivityList.map(_.latest.valid).reduce(_ && _)
  val canAdvance = minTime > out.old.bits.time
  out.latest.valid := Mux(old.valid, canAdvance, allInputsDefined)
  out.latest.bits.time := minTime

  //Out fire suspect.
  sensitivityList.foreach(in => in.observed := in.unchanged || (out.fire && in.definedUntil === minTime))
  when (out.fire) {
    old := out.latest
  }

  def valueOf[T <: Data](n: TimestampedTuple[T]): T = {
    require(sensitivities(n), s"Node ${n} not included in sensitivity list.")
    n.valueAt(minTime)
  }
}


class CombinationalAndTest(timeout: Int = 50000)(implicit p: Parameters) extends UnitTest(timeout) {
  val aPeriodPS = 10
  val bPeriodPS = 5
  // Use a clock source to provide data-input stimulus to resuse code we already have
  val refInputA = Module(new ClockSourceReference(aPeriodPS, initValue = 0))
  val refInputB = Module(new ClockSourceReference(bPeriodPS, initValue = 0))
  val refAnd = refInputA.io.clockOut && refInputB.io.clockOut


  val modelInputA = TimestampedSource(DecoupledDelayer(
    Module(new ClockSource(aPeriodPS, initValue = false)).clockOut,
     0.5))

  val modelInputB = TimestampedSource(DecoupledDelayer(
    Module(new ClockSource(bPeriodPS, initValue = false)).clockOut,
    0.5))

  val and = new CombLogic(Bool(), modelInputA, modelInputB) {
    out.latest.bits.data := valueOf(modelInputA) && valueOf(modelInputB)
  }

  io.finished := TimestampedTokenTraceEquivalence(refAnd, and.out, timeout)
}
