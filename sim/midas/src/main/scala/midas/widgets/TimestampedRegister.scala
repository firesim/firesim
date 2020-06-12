// See LICENSE for license details.

package midas.widgets

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.unittest._

sealed trait EdgeSensitivity {
  def toVParam: String
}
case object Posedge extends EdgeSensitivity { def toVParam = "POSEDGE" }
case object Negedge extends EdgeSensitivity { def toVParam = "NEGEDGE" }

class TimestampedRegister[T <: Data](gen: T, edgeSensitivity: EdgeSensitivity, init: Option[T] = None) extends MultiIOModule {
  val simClock  = IO(Flipped(new TimestampedTuple(Bool())))
  val d      = IO(Flipped(new TimestampedTuple(gen)))
  val q      = IO(new TimestampedTuple(gen))

  val reg = RegInit({
    val w = Wire(new TimestampedToken(gen))
    w.time := 0.U
    w.data := init.getOrElse(0.U.asTypeOf(w.data))
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

  val observeEdge = q.observed && d.definedBefore(simClock.latest.bits.time)
  simClock.observed := !latchingEdge || q.observed && d.definedBefore(simClock.latest.bits.time)
  d.observed := Mux(latchingEdge,
    !d.definedBefore(simClock.latest.bits.time) || simClock.latest.bits.time === d.latest.bits.time + 1.U,
    simClock.definedUntil >= d.definedUntil)
}


class ReferenceRegisterImpl(dataWidth: Int, edgeSensitivity: EdgeSensitivity, initValue: Option[Int])
    extends BlackBox(Map(
      "DATA_WIDTH" -> dataWidth,
      "EDGE_SENSE" -> edgeSensitivity.toVParam,
      "INIT_VALUE" -> initValue.getOrElse(0).toInt)) with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clock = Input(Bool())
    val reset = Input(Reset())
    val d     = Input(UInt(dataWidth.W))
    val q     = Output(UInt(dataWidth.W))
  })
  addResource("/midas/widgets/ReferenceRegisterImpl.sv")
}

class ReferenceRegister[T <: Data](gen: T, edgeSensitivity: EdgeSensitivity, init: Option[T] = None) extends RawModule { 
  val d   = IO(Input(gen))
  val q   = IO(Output(gen))
  val reset = IO(Input(Reset()))
  val clock = IO(Input(Bool()))

  val reg = Module(new ReferenceRegisterImpl(gen.getWidth, edgeSensitivity, init.map(_.litValue.toInt)))
  reg.io.clock := clock
  reg.io.reset := reset
  reg.io.d := d
  q := reg.io.q
}

object TimestampedRegister {
  private[midas] def instantiateAgainstReference[T <: Data](
    edgeSensitivity: EdgeSensitivity,
    initValue: Option[T],
    clocks: (TimestampedTuple[T], Bool),
    d: (TimestampedTuple[T], T)): (TimestampedRegister[T], ReferenceRegister[T]) = {

    def tpe: T = d._1.underlyingType
    val (modelClock, refClock) = clocks
    val (modelD, refD) = d
    val refReg   = Module(new ReferenceRegister(tpe, edgeSensitivity, initValue))
    refReg.reset := false.B
    refReg.clock := refClock
    refReg.d     := refD

    val modelReg   = Module(new TimestampedRegister(tpe, edgeSensitivity, initValue))
    modelReg.simClock <> modelClock
    modelReg.d <> modelD
    (modelReg, refReg)
  }
}

class TimestampedRegisterTest(edgeSensitivity: EdgeSensitivity, timeout: Int = 50000)(implicit p: Parameters) extends UnitTest(timeout) {
  val refClock = Module(new ClockSourceReference(1000, initValue = 0))
  // Use a clock source to provide data-input stimulus to resuse code we already have
  val refInput = Module(new ClockSourceReference(373, initValue = 0))

  val modelClock = TimestampedSource(DecoupledDelayer(
    Module(new ClockSource(1000, initValue = false)).clockOut,
    0.5))
  val modelInput = TimestampedSource(DecoupledDelayer(
    Module(new ClockSource(373, initValue = false)).clockOut,
    0.25))
  val (modelReg, refReg) = TimestampedRegister.instantiateAgainstReference(
    edgeSensitivity,
    initValue = None,
    clocks = (modelClock, refClock.io.clockOut),
    d = (modelInput, refInput.io.clockOut))

  val targetTime = TimestampedTokenTraceEquivalence(refReg.q, modelReg.q)
  io.finished := targetTime > (timeout / 2).U
}

