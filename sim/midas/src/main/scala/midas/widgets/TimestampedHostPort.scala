// See LICENSE for license details.

package midas.widgets

import chisel3._
import chisel3.util._
import chisel3.experimental.chiselName

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.unittest._
import freechips.rocketchip.tilelink.{LFSRNoiseMaker, LFSR64}

trait HasTimestampConstants {
  val timestampWidth = 64
}

class TimestampedToken[T <: Data](private val gen: T) extends Bundle with HasTimestampConstants {
  def underlyingType(): T = gen
  val data = Output(gen)
  val time = Output(UInt(timestampWidth.W))
}

class TimestampedTuple[T <: Data](private val gen: T) extends Bundle with HasTimestampConstants { 
  def underlyingType(): T = gen
  val old     = Output(Valid(new TimestampedToken(gen)))
  val latest   = Output(Valid(new TimestampedToken(gen)))
  val observed = Input(Bool())

  def definedAt(time: UInt): Bool =
    (latest.valid && latest.bits.time >= time) || (old.valid && old.bits.time >= time)

  def definedBefore(time: UInt): Bool =
    (latest.valid && (latest.bits.time + 1.U) >= time) || (old.valid && (old.bits.time + 1.U) >= time)

  def valueAt(time: UInt): T = {
    val usesNewValue = latest.valid && time === latest.bits.time
    Mux(usesNewValue, latest.bits.data, old.bits.data)
  }

  def valueBefore(time: UInt): T = valueAt(time - 1.U)

  def definedUntil(): UInt = Mux(latest.valid, latest.bits.time, Mux(old.valid, old.bits.time, 0.U))
  def unchanged(): Bool = old.bits.data.asUInt === latest.bits.data.asUInt
}


class TimestampedSource[T <: Data](gen: DecoupledIO[TimestampedToken[T]]) extends MultiIOModule {
  val source = IO(Flipped(gen))
  val value  = IO(new TimestampedTuple(gen.bits.data))

  val init = RegInit(false.B)
  val old  = Reg(source.bits.cloneType)

  assert(!source.valid || !init || source.bits.time > old.time, "Token stream must advance forward in time")

  when(!init && source.valid) {
    assert(source.bits.time === 0.U, "First token must be timestamped to time 0")
    old := source.bits
    init := true.B
  }.elsewhen(init && source.valid && (value.observed || value.unchanged)) {
    old := source.bits
  }
  source.ready := !init || (value.observed || value.unchanged)
  value.old.valid := init
  value.old.bits := old
  value.latest.valid := source.valid
  value.latest.bits := source.bits
}

object TimestampedSource {
  def apply[T <: Data](in: DecoupledIO[TimestampedToken[T]]): TimestampedTuple[T] = {
    val mod = Module(new TimestampedSource(in.cloneType))
    mod.source <> in
    mod.value
  }
}

class TimestampedSink[T <: Data](gen: T) extends MultiIOModule {
  val value = IO(Flipped(new TimestampedTuple(gen)))
  val sink  = IO(Decoupled(new TimestampedToken(gen)))
  sink.valid := value.latest.valid
  sink.bits  := value.latest.bits
  value.observed := sink.ready
}

object TimestampedSink {
  def apply[T <: Data](in: TimestampedTuple[T]): DecoupledIO[TimestampedToken[T]] = {
    val mod = Module(new TimestampedSink(in.underlyingType))
    mod.value <> in
    mod.sink
  }
}

class ReferenceTimestamperImpl(dataWidth: Int) extends BlackBox(Map("DATA_WIDTH" -> dataWidth))
  with HasBlackBoxResource {
  addResource("/midas/widgets/ReferenceTimestamperImpl.sv")
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val value = Input(UInt(dataWidth.W))
    val timestamped = Decoupled(new TimestampedToken(UInt(dataWidth.W)))
  })
}

class ReferenceTimestamper[T <: Data](gen: T) extends MultiIOModule {
  val value = IO(Input(gen))
  val timestamped = IO(Decoupled(new TimestampedToken(gen)))

  val ts = Module(new ReferenceTimestamperImpl(value.getWidth))
  ts.io.clock := clock
  ts.io.reset := reset
  ts.io.value := value.asUInt
  timestamped.valid := ts.io.timestamped.valid
  timestamped.bits := ts.io.timestamped.bits.asTypeOf(timestamped.bits)
  ts.io.timestamped.ready := timestamped.ready
}

object ReferenceTimestamper {
  def apply[T <: Data](value: T): DecoupledIO[TimestampedToken[T]] = {
    val timestamper = Module(new ReferenceTimestamper(value.cloneType))
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
  val time = IO(Output(UInt(timestampWidth.W)))
  // Consume null-tokens greedily but wait for both channels when there's a transition in either. 
  // These non-null messages should be indentical
  a.observed := (a.old.valid && a.unchanged) || b.latest.valid && b.old.valid && !b.unchanged
  b.observed := (b.old.valid && b.unchanged) || a.latest.valid && a.old.valid && !a.unchanged

  // Check that last takes on indentical values, including init
  assert(!a.old.valid || !b.old.valid || a.old.bits.data.asUInt === b.old.bits.data.asUInt,
    "Non-null messages do not have matching data")
  // Check that non-null messages arrive at the same cycles. (The data check
  // is captured by the assertion above, since the transition is observed
  // on the same host cycle.)
  assert((!a.old.valid || !b.old.valid) || (a.unchanged || b.unchanged) || (!a.latest.valid || !b.latest.valid) ||
     a.latest.bits.time === b.latest.bits.time,
    "Non-null messages do not arrive at the same target times")
  time := Mux(a.old.valid && b.old.valid,
    Mux(a.old.bits.time > b.old.bits.time, a.old.bits.time, b.old.bits.time),
    0.U
  )
}

object TimestampedTokenTraceEquivalence {
  @chiselName
  def apply[T <: Data](a: TimestampedTuple[T], b: TimestampedTuple[T]): UInt = {
    val checker = Module(new TimestampedTokenTraceChecker(a.underlyingType))
    println(checker)
    checker.a <> a
    checker.b <> b
    checker.time
  }
  def apply[T <: Data](a: DecoupledIO[TimestampedToken[T]], b: DecoupledIO[TimestampedToken[T]]): UInt =
    apply(TimestampedSource(a), TimestampedSource(b))

  def apply[T <: Data](reference: T, model: TimestampedTuple[T]): UInt =
    apply(TimestampedSource(ReferenceTimestamper(reference)), model)

  def apply[T <: Data](reference: T, model: DecoupledIO[TimestampedToken[T]]): UInt = 
    apply(TimestampedSource(ReferenceTimestamper(reference)), TimestampedSource(model))
}


class DecoupledDelayer[T <: Data](gen: DecoupledIO[T], q: Double) extends MultiIOModule {
  val i = IO(Flipped(gen))
  val o = IO(gen)
  val allow = ((q * 65535.0).toInt).U <= LFSRNoiseMaker(16, i.valid)
  o <> i
  o.valid := i.valid && allow
  i.ready := o.ready && allow
}

// Inspired by RC TLDelayer
object DecoupledDelayer {
  def apply[T <: Data](in: DecoupledIO[T], q: Double): DecoupledIO[T] = {
    val delay = Module(new DecoupledDelayer(in.cloneType, q))
    delay.i <> in
    delay.o
  }
}
