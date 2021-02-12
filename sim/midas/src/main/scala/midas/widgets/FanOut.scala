// See LICENSE for license details.

package midas.widgets

import chisel3._
import chisel3.util._
import chisel3.experimental.chiselName

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.unittest.{UnitTest}
import freechips.rocketchip.util.{DecoupledHelper}

object FanOut {
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
  val (refInput, modelInput)  = ClockSource.instantiateAgainstReference(clockPeriodPS, initValue = false)
  val Seq(modelOutA, modelOutB) = FanOut(modelInput, 2).map(o => DecoupledDelayer(TimestampedSink(PipeStage(o)), 0.5))
  val aFinished = TimestampedTokenTraceEquivalence(refInput, modelOutA, timeout)
  val bFinished = TimestampedTokenTraceEquivalence(refInput, modelOutB, timeout)
  io.finished := aFinished && bFinished
}

