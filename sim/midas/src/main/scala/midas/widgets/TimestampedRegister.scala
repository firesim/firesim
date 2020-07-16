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
    val w = Wire(ValidIO(new TimestampedToken(gen)))
    w := DontCare
    w.valid := false.B
    w
  })

  val simClockAdvancing = simClock.latest.valid && simClock.old.valid
  val latchingEdge = edgeSensitivity match {
    case Posedge => simClockAdvancing && ~simClock.old.bits.data &&  simClock.latest.bits.data
    case Negedge => simClockAdvancing &&  simClock.old.bits.data && ~simClock.latest.bits.data
  }
  val observeClockEdge = WireDefault(false.B)
  simClock.observed := !simClock.old.valid || !latchingEdge || observeClockEdge

  val observeDEdge = WireDefault(false.B)
  d.observed := !d.old.valid || d.unchanged || observeDEdge

  q.latest.bits := q.old.bits
  q.latest.valid := !q.old.valid || q.latest.bits.time > q.old.bits.time

  when(!q.old.valid) {
    q.latest.bits.time := 0.U
    q.latest.bits.data := init.getOrElse(0.U.asTypeOf(q.latest.bits.data))
  // Clock leads data -> can always advance to clock.latest.time or clock.time - 1
  }.elsewhen(simClock.definedUntil > d.definedUntil) {
    // Any transition on D happens before an edge so we can safely acknowledge it
    observeDEdge := true.B
    when(latchingEdge) {
      // Only true if D transitions defined one timestep before the clock edge
      // Emit possible transition at @ C_t
      when(d.definedBefore(simClock.latest.bits.time)) {
        q.latest.bits.time := simClock.latest.bits.time
        q.latest.bits.data := d.valueBefore(simClock.latest.bits.time)
        observeClockEdge := q.observed
      // More generally we need to wait until D is defined until C_t - 1
      // Present a null token up for C_t - 1
      }.otherwise{
        q.latest.bits.time := simClock.latest.bits.time - 1.U
      }
    }.otherwise{
      // Not an edge, so no possible change in output. Null token @ C_t
      q.latest.bits.time := simClock.definedUntil
    }
    // D_t >= C_t
  }.otherwise{
    // The furthest we can possibly advance to is D_t, if it is unchanged WRT
    // to the output.  It is insufficient to check that D is unchanged, as the
    // init value of the register may differ from D
    when((!d.latest.valid || d.unchanged) && d.old.bits.data.asUInt === q.old.bits.data.asUInt ) {
      q.latest.bits.time := d.definedUntil
      // Neglect the clock edge if there is one.
      observeClockEdge := true.B
    // But if there is a D transtion we need make sure all clock edges see the old value.
    }.elsewhen(simClock.latest.valid) {
      // Can always advance to @ C_t; may not be null if edge.
      q.latest.bits.time := simClock.latest.bits.time
      when (latchingEdge) {
        q.latest.bits.data := d.valueBefore(simClock.latest.bits.time)
        observeClockEdge := q.observed
      }
    }
  }

  q.old := reg
  when(q.fire) {
    reg := q.latest
  }
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
    gen: =>T,
    edgeSensitivity: EdgeSensitivity,
    initValue: Option[T],
    clocks: (Bool, TimestampedTuple[Bool]),
    d: Option[(T, TimestampedTuple[T])] = None): (ReferenceRegister[T], TimestampedRegister[T]) = {

    val (refClock, modelClock) = clocks
    val refReg   = Module(new ReferenceRegister(gen, edgeSensitivity, initValue))
    refReg.reset := false.B
    refReg.clock := refClock
    val modelReg   = Module(new TimestampedRegister(gen, edgeSensitivity, initValue))
    modelReg.simClock <> modelClock

    d.foreach { case (refD, modelD) =>
      refReg.d := refD
      modelReg.d <> modelD
    }
    (refReg, modelReg)
  }
}

class TimestampedRegisterTest(
    edgeSensitivity: EdgeSensitivity,
    clockPeriodPS: Int,
    inputPeriodPS: Int,
    timeout: Int = 50000)(implicit p: Parameters) extends UnitTest(timeout) {
  val refClock = Module(new ClockSourceReference(clockPeriodPS, initValue = 0))
  // Use a clock source to provide data-input stimulus to resuse code we already have
  val refInput = Module(new ClockSourceReference(inputPeriodPS, initValue = 0))

  val modelClock = TimestampedSource(DecoupledDelayer(
    Module(new ClockSource(clockPeriodPS, initValue = false)).clockOut,
    0.5))
  val modelInput = TimestampedSource(DecoupledDelayer(
    Module(new ClockSource(inputPeriodPS, initValue = false)).clockOut,
    0.25))
  val (refReg, modelReg) = TimestampedRegister.instantiateAgainstReference(
    Bool(),
    edgeSensitivity,
    initValue = None,
    clocks = (refClock.io.clockOut, modelClock),
    d = Some((refInput.io.clockOut, modelInput)))

  val targetTime = TimestampedTokenTraceEquivalence(refReg.q, modelReg.q)
  io.finished := targetTime > (timeout / 2).U
}

class TimestampedRegisterLoopbackTest(edgeSensitivity: EdgeSensitivity, clockPeriodPS: Int, timeout: Int = 50000) extends UnitTest(timeout) {
  val (refClock, modelClock) = ClockSource.instantiateAgainstReference(clockPeriodPS)
  val (refReg, modelReg) = TimestampedRegister.instantiateAgainstReference(
    Bool(),
    edgeSensitivity,
    initValue = None,
    clocks = (refClock, TimestampedSource(DecoupledDelayer(modelClock, 0.5))))

  refReg.d := !refReg.q

  val Seq(modelOut, modelLoopback) = FanOut(PipeStage(modelReg.q), 2)
  modelReg.d <> (new CombLogic(Bool(), modelLoopback){
    out.latest.bits.data := !valueOf(modelLoopback)
  }).out

  val targetTime = TimestampedTokenTraceEquivalence(refReg.q, modelOut)
  io.finished := targetTime > (timeout / 2).U
}
