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
  val data = Output(gen.cloneType)
  val time = Output(UInt(timestampWidth.W))
}

class TimestampedTuple[T <: Data](private val gen: T) extends Bundle with HasTimestampConstants { 
  val old     = Output(Valid(new TimestampedToken(gen.cloneType)))
  val latest   = Output(Valid(new TimestampedToken(gen.cloneType)))
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
  val source = IO(Flipped(gen.cloneType))
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
    val mod = Module(new TimestampedSource(in))
    mod.source <> in
    mod.value
  }
}

trait EdgeSensitivity
case object Posedge extends EdgeSensitivity
case object Negedge extends EdgeSensitivity


class TimestampedRegister[T <: Data](gen: T, edgeSensitivity: EdgeSensitivity, init: Option[T] = None) extends MultiIOModule {
  val simClock  = IO(Flipped(new TimestampedTuple(Bool())))
  val d      = IO(Flipped(new TimestampedTuple(gen)))
  val q      = IO(new TimestampedTuple(gen))

  val reg = RegInit({
    val w = Wire(new TimestampedToken(gen))
    w.time := 0.U
    init.foreach(w.data := _)
    w
  })

  val simClockAdvancing = simClock.latest.valid && simClock.old.valid
  val latchingEdge = edgeSensitivity match {
    case Posedge => simClockAdvancing && ~simClock.old.bits.data &&  simClock.latest.bits.data
    case Negedge => simClockAdvancing &&  simClock.old.bits.data && ~simClock.latest.bits.data
  }

  q.old.valid := true.B
  q.old.bits  := reg
  q.latest.valid := simClockAdvancing && (!latchingEdge || d.definedBefore(simClock.latest.bits.time))
  q.latest.bits.time  := simClock.latest.bits.time
  q.latest.bits.data  := Mux(latchingEdge, d.valueBefore(simClock.latest.bits.time), q.old.bits.data)

  when(q.latest.valid && (q.observed || q.unchanged)) {
    reg := q.latest.bits
  }

  simClock.observed := !latchingEdge || q.observed && d.definedBefore(simClock.latest.bits.time)
  d.observed := Mux(latchingEdge,
    simClock.latest.bits.time === d.latest.bits.time + 1.U,
    simClock.definedUntil >= d.definedUntil)
}

class TimestampedSink[T <: Data](gen: T) extends MultiIOModule {
  val value = IO(Flipped(new TimestampedTuple(gen)))
  val sink  = IO(Decoupled(new TimestampedToken(gen)))
  sink.valid := value.latest.valid
  sink.bits  := value.latest.bits
  value.observed := sink.ready
}


class TimestampedRegisterTest(timeout: Int = 50000)(implicit p: Parameters) extends UnitTest(timeout) {
  io.finished := true.B
}

class ClockSource(periodPS: BigInt, dutyCycle: Int = 50, initValue: Boolean = false) extends MultiIOModule {
  val clockOut = IO(Decoupled(new TimestampedToken(Bool())))
  val time = RegInit(0.U(clockOut.bits.timestampWidth.W))
  val highTime = (periodPS * dutyCycle) / 100
  val lowTime = periodPS - highTime
  val currentValue = RegInit(initValue.B)
  clockOut.valid := true.B
  clockOut.bits.time := time
  clockOut.bits.data := currentValue

  when(clockOut.ready) {
    time := Mux(currentValue, highTime.U, lowTime.U) + time
    currentValue := ~currentValue
  }
}

class ClockSourceReference(periodPS: BigInt, dutyCycle: Int = 50, initValue: Int = 0)
    extends BlackBox(Map("PERIOD_PS" -> periodPS, "DUTY_CYCLE" -> dutyCycle, "INIT_VALUE" -> initValue))
    with HasBlackBoxResource {
  addResource("/midas/widgets/ClockSourceReference.sv")
  val io = IO(new Bundle { val clockOut = Output(Bool()) })
}

class ReferenceTimestamperImpl(dataWidth: Int) extends BlackBox(Map("DATA_WIDTH" -> dataWidth))
  with HasBlackBoxResource {
  addResource("/midas/widgets/ReferenceTimestamper.sv")
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Reset())
    val value = Input(UInt(dataWidth.W))
    val timestamped = Decoupled(new TimestampedToken(UInt(dataWidth.W)))
  })
}

class ReferenceTimestamper[T <: Data](gen: T) extends MultiIOModule {
  val value = IO(Input(gen.cloneType))
  val timestamped = IO(Decoupled(new TimestampedToken(gen.cloneType)))

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
    val timestamper = Module(new ReferenceTimestamper(value))
    timestamper.value := value
    timestamper.timestamped
  }
}

class ClockSourceTest(periodPS: BigInt, initValue: Boolean, timeout: Int = 100000)(implicit p: Parameters) extends UnitTest(timeout) with HasTimestampConstants{
  val reference = ReferenceTimestamper(Module(new ClockSourceReference(periodPS, initValue = if (initValue) 1 else 0)).io.clockOut)
  val model = Module(new ClockSource(periodPS, initValue = initValue))
  val targetTime = TimestampedTokenTraceEquivalence(reference, model.clockOut)
  io.finished := targetTime > (timeout / 2).U
}


// This checks two timestamped token streams model the same underlying target
// signal by comparing non-null messages (transition times + values). Either
// stream can provide different numbers of null-messages
object TimestampedTokenTraceEquivalence {
  @chiselName
  def apply[T <: Data](aSource: DecoupledIO[TimestampedToken[T]], bSource: DecoupledIO[TimestampedToken[T]]): UInt = {
    val a = TimestampedSource(aSource)
    val b = TimestampedSource(bSource)
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
    Mux(a.old.valid && b.old.valid,
      Mux(a.old.bits.time > b.old.bits.time, a.old.bits.time, b.old.bits.time),
      0.U
    )
  }
}


class DecoupledDelayer[T <: Data](gen: DecoupledIO[T], q: Double) extends MultiIOModule {
  val i = IO(Flipped(gen.cloneType))
  val o = IO(gen.cloneType)
  val allow = ((q * 65535.0).toInt).U <= LFSRNoiseMaker(16, i.valid)
  o <> i
  o.valid := i.valid && allow
  i.ready := o.ready && allow
}

// Inspired by RC TLDelayer
object DecoupledDelayer {
  def apply[T <: Data](in: DecoupledIO[T], q: Double): DecoupledIO[T] = {
    val delay = Module(new DecoupledDelayer(in, q))
    delay.i <> in
    delay.o
  }
}
