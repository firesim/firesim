// See LICENSE for license details.

package midas.widgets

import chisel3._
import chisel3.util._
import chisel3.experimental.chiselName

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.unittest.{UnitTest}
import freechips.rocketchip.util.{EnhancedChisel3Assign, DecoupledHelper}


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
  out.old :<= old

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

object FanOut {
  @chiselName
  def apply[T <: Data](in: TimestampedTuple[T],
      count: Int,
      depth: Int = 16,
      pipe: Boolean = false,
      flow: Boolean = false,
      suggestedNames: Seq[String] = Nil): Seq[TimestampedTuple[T]] = {
    require(count > 1)
    require(suggestedNames.isEmpty || suggestedNames.size == count)
    val producer = TimestampedSink(in)
    val consumerQueues = Seq.fill(count)(Module(new Queue(producer.bits.cloneType, depth, pipe, flow)))
    val helper = new DecoupledHelper(producer.valid +: consumerQueues.map(_.io.enq.ready))
    consumerQueues foreach { q =>
      q.io.enq.bits := producer.bits
      q.io.enq.valid := helper.fire(q.io.enq.ready)
    }
    producer.ready := helper.fire(producer.valid)
    val nameOpts = suggestedNames.map(Some(_))
    val outs = consumerQueues.zipAll(nameOpts, consumerQueues.head, None).map({ case (q, name) => TimestampedSource(q.io.deq, name) })
    outs
  }

  def apply[T <: Data](in: TimestampedTuple[T], names: String*): Seq[TimestampedTuple[T]] = apply(in, names.size, suggestedNames = names)

    //val (fanOuts, observedOrObserving) = Seq.tabulate(count)({ i =>
    //  val out = Wire(new TimestampedTuple(in.underlyingType))
    //  val old = RegInit({
    //    val w = Wire(ValidIO(new TimestampedToken(in.underlyingType)))
    //    w := DontCare
    //    w.valid := false.B
    //    w
    //  })
    //  val hasBeenObserved = RegInit(false.B)
    //  when(in.fire) {
    //    hasBeenObserved := false.B
    //  }.elsewhen(out.fire) {
    //    hasBeenObserved := true.B
    //  }
    //  val observedOrObserving = out.observed || hasBeenObserved

    //  when (out.fire) {
    //    old := out.latest
    //  }

    //  out.old := old
    //  out.latest.valid := !hasBeenObserved && in.latest.valid
    //  out.latest.bits := in.latest.bits
    //  (out, observedOrObserving)
    //}).unzip

    //in.observed := in.unchanged || observedOrObserving.reduce(_ && _)
    //fanOuts
  //}
}

object PipeStage {
  def apply[T <: Data](in: TimestampedTuple[T]): TimestampedTuple[T] = {
    val out = Wire(in.cloneType)
    // This might not be required.
    val old = RegInit({
      val w = Wire(ValidIO(new TimestampedToken(in.underlyingType)))
      w := DontCare
      w.valid := false.B
      w
    })
    val latest = RegInit({
      val w = Wire(ValidIO(new TimestampedToken(in.underlyingType)))
      w := DontCare
      w.valid := false.B
      w
    })

    when(out.fire || !out.latest.valid) {
      old    := in.old
      latest := in.latest
    }
    in.observed := out.fire || !out.latest.valid
    out.latest := latest
    out.old := old
    out
  }
}


class FanOutTest(timeout: Int = 50000)(implicit p: Parameters) extends UnitTest(timeout) {
  val clockPeriodPS = 500
  // Use a clock source to provide data-input stimulus to resuse code we already have
  val refInput = Module(new ClockSourceReference(clockPeriodPS, initValue = 0))
  val modelInput = TimestampedSource(DecoupledDelayer(
    Module(new ClockSource(clockPeriodPS, initValue = false)).clockOut,
    0.5))
  val Seq(modelOutA, modelOutB) = FanOut(modelInput, 2).map(o => DecoupledDelayer(TimestampedSink(PipeStage(o)), 0.5))
  val targetTimeA = TimestampedTokenTraceEquivalence(refInput.io.clockOut, modelOutA)
  val targetTimeB = TimestampedTokenTraceEquivalence(refInput.io.clockOut, modelOutB)
  io.finished := targetTimeA > (timeout / 2).U && targetTimeB > (timeout / 2).U
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

  val targetTime = TimestampedTokenTraceEquivalence(refAnd, and.out)
  io.finished := targetTime > (timeout / 2).U
}

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
