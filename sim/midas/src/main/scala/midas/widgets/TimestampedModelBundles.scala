// See LICENSE for license details.

package midas.widgets

import chisel3._
import chisel3.util._

import freechips.rocketchip.tilelink.{LFSRNoiseMaker}

trait HasTimestampConstants {
  val timestampWidth = 64
  def timestampFType = firrtl.ir.UIntType(firrtl.ir.IntWidth(timestampWidth))
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
  def unchanged(): Bool = old.bits.data.asUInt === latest.bits.data.asUInt && old.valid
  def fire(): Bool = latest.valid && observed
}


class TimestampedSource[T <: Data](gen: DecoupledIO[TimestampedToken[T]]) extends MultiIOModule {
  val source = IO(Flipped(gen))
  val value  = IO(new TimestampedTuple(gen.bits.underlyingType))

  val old = RegInit({
    val w = Wire(ValidIO(new TimestampedToken(gen.bits.underlyingType)))
    w := DontCare
    w.valid := false.B
    w
  })

  assert(!source.valid || !old.valid || source.bits.time > old.bits.time, "Token stream must advance forward in time")
  assert(old.valid || !source.valid || source.bits.time === 0.U, "First token must be timestamped to time 0")

  when(value.fire) {
    old.valid := true.B
    old.bits := source.bits
  }

  source.ready := value.observed
  value.old := old
  value.latest.valid := source.valid
  value.latest.bits := source.bits
}

object TimestampedSource {
  def apply[T <: Data](in: DecoupledIO[TimestampedToken[T]], name: Option[String] = None): TimestampedTuple[T] = {
    val mod = Module(new TimestampedSource(in.cloneType))
    name.foreach(n => mod.suggestName(n))
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

object TupleQueue {
  def apply[T <: Data](in: TimestampedTuple[T], depth: Int, pipe: Boolean = false, flow: Boolean = false): TimestampedTuple[T] =
    TimestampedSource(Queue(TimestampedSink(in), depth, pipe, flow))
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
