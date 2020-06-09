// See LICENSE for license details.

package midas.widgets

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.unittest._
import freechips.rocketchip.tilelink.LFSR64

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

class ClockSource(periodPS: BigInt, dutyCycle: Int = 50, init: Bool = false.B) extends MultiIOModule {
  val clockOut = IO(Decoupled(new TimestampedToken(Bool())))
  val time = RegInit(0.U(clockOut.timestampWidth.W))
  val highTime = (period * dutyCycle) / 100
  val lowTime = periodPS - highTime
  val currentValue = RegInit(init)
  clockOut.valid := true.B
  clockOut.bits.time := time
  clockOut.bits.data := currentValue

  when(clockOut.ready) {
    time := Mux(currentValue, highTime, lowTime) + time
    currentValue := ~currentValue
  }
}

class ClockSourceReference(periodPS: BigInt, dutyCycle: Int = 50, Int, initValue: String = "TRUE")
    extends BlackBox(Map("PERIOD_PS" -> period_ps, "DUTY_CYCLE" -> dutyCycle, "INIT_VALUE" -> init)) {
  val io = IO(new Bundle { val clockOut = Output(Clock()) })
}

