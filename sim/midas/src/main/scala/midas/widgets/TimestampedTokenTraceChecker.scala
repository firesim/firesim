// See LICENSE for license details.

package midas.widgets

import chisel3._
import chisel3.util._
import chisel3.experimental.chiselName

class ReferenceTimestamperImpl(dataWidth: Int, depth: Int) extends BlackBox(
    Map("DATA_WIDTH" -> dataWidth, "LOG2_NUM_SAMPLES" -> log2Ceil(depth))) with HasBlackBoxResource {
  addResource("/midas/widgets/ReferenceTimestamperImpl.sv")
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val value = Input(UInt(dataWidth.W))
    val timestamped = Decoupled(new TimestampedToken(UInt(dataWidth.W)))
  })
}

/**
  * Translates a non-host-decoupled signal into a timestamped form, by sampling
  * a signal and annotating it with the current simulation time
  *
  * @param depth The depth of the internal buffer storing samples of the
  *   reference. Set = to the host timeout to prevent overflow
  */

class ReferenceTimestamper[T <: Data](gen: T, depth: Int) extends MultiIOModule {
  val value = IO(Input(gen))
  val timestamped = IO(Decoupled(new TimestampedToken(gen)))

  val ts = Module(new ReferenceTimestamperImpl(value.getWidth, depth))
  ts.io.clock := clock
  ts.io.reset := reset
  ts.io.value := value.asUInt
  timestamped.valid := ts.io.timestamped.valid
  timestamped.bits := ts.io.timestamped.bits.asTypeOf(timestamped.bits)
  ts.io.timestamped.ready := timestamped.ready
}

object ReferenceTimestamper {
  def apply[T <: Data](value: T, depth: Int = 1 << 16): DecoupledIO[TimestampedToken[T]] = {
    val timestamper = Module(new ReferenceTimestamper(value.cloneType, depth))
    timestamper.value := value
    timestamper.timestamped
  }
}

// This checks two timestamped token streams model the same underlying target
// signal by comparing non-null messages (transition times + values). Either
// stream can provide different numbers of null-messages
class TimestampedTokenTraceChecker[T <: Data](gen: T) extends MultiIOModule with HasTimestampConstants {
  // By convention a -> reference, b -> model when not compairing two models
  val a = IO(Flipped(new TimestampedTuple(gen)))
  val b = IO(Flipped(new TimestampedTuple(gen)))
  val advancedToTime = IO(Output(UInt(timestampWidth.W)))
  // Consume null-tokens greedily but wait for both channels when there's a transition in either. 
  // These non-null messages should be indentical
  a.observed := b.latest.valid && !b.unchanged || a.unchanged
  b.observed := a.latest.valid && !a.unchanged || b.unchanged

  // Check that last takes on indentical values, including init
  assert(!a.old.valid || !b.old.valid || a.old.bits.data.asUInt === b.old.bits.data.asUInt,
    "Non-null messages do not have matching data")
  // Check that non-null messages arrive at the same cycles. (The data check
  // is captured by the assertion above, since the transition is observed
  // on the same host cycle.)
  assert((!a.old.valid || !b.old.valid) || (a.unchanged || b.unchanged) || (!a.latest.valid || !b.latest.valid) ||
     a.latest.bits.time === b.latest.bits.time,
    "Non-null messages do not arrive at the same target times")
  advancedToTime := Mux(a.old.valid && b.old.valid,
    Mux(a.old.bits.time > b.old.bits.time, a.old.bits.time, b.old.bits.time),
    0.U
  )
}

object TimestampedTokenTraceEquivalence {
  @chiselName
  def apply[T <: Data](a: TimestampedTuple[T], b: TimestampedTuple[T], hostTimeout: Int): Bool = {
    val checker = Module(new TimestampedTokenTraceChecker(a.underlyingType))
    checker.a <> a
    checker.b <> b
    checker.advancedToTime > (hostTimeout / 8).U
  }
  def apply[T <: Data](a: DecoupledIO[TimestampedToken[T]], b: DecoupledIO[TimestampedToken[T]], hostTimeout: Int): Bool =
    apply(TimestampedSource(a), TimestampedSource(b), hostTimeout)

  def apply[T <: Data](reference: T, model: TimestampedTuple[T], hostTimeout: Int): Bool =
    apply(TimestampedSource(ReferenceTimestamper(reference, hostTimeout)), model, hostTimeout)

  def apply[T <: Data](reference: T, model: DecoupledIO[TimestampedToken[T]], hostTimeout: Int): Bool =
    apply(TimestampedSource(ReferenceTimestamper(reference, hostTimeout)), TimestampedSource(model), hostTimeout)
}
