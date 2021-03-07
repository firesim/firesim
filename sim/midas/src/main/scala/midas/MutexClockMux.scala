// See LICENSE for license details.

package midas.widgets

import chisel3._
import chisel3.util._
import chisel3.experimental.BaseModule

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.unittest.{UnitTest}


class MutexClockMux(width: Int, sync: Int = 2) extends MultiIOModule with HasTimestampConstants {
  val clocksIn   = IO(Flipped(Vec(width, new TimestampedTuple(Bool()))))
  val sel      = IO(Flipped(new TimestampedTuple(UInt(log2Ceil(width).W))))
  val clockOut = IO(new TimestampedTuple(Bool()))

  // TODO: Init
  val selOH = UIntToOH(sel.old.bits.data).asBools.map { _ && sel.old.valid }
  val syncNonEmpty = Seq.fill(width)(Wire(Bool()))

  val outOld = RegInit({
    val w = Wire(ValidIO(new TimestampedToken(Bool())))
    w := DontCare
    w.valid := false.B
    w
  })

  // Keeps around references, and improves naming without the boilerplate of a Module
  class PerClockLogic(val iClock: TimestampedTuple[Bool], val enable: Bool, idx: Int) {
    val queue = Module(new Queue(timestampCType, sync, pipe = true))
    val negedgeRequired = RegInit(false.B)
    val syncOccupancy = RegInit(0.U(log2Ceil(sync + 1).W))
    syncNonEmpty(idx) := (syncOccupancy =/= 0.U) || negedgeRequired

    val altSyncOccupied = syncNonEmpty.zipWithIndex
    .collect { case (sync, j) if j != idx => sync }
    .reduce { _ || _ }

    val posedge = !iClock.unchanged && iClock.latest.bits.data
    val negedge = !iClock.unchanged && !iClock.latest.bits.data

    queue.io.enq.bits := iClock.latest.bits.time
    queue.io.enq.valid := iClock.latest.valid && posedge &&
      (!(enable && !altSyncOccupied) || clockOut.observed)
    iClock.observed := //iClock.unchanged ||
      // Proceed if we have space for a token in our posedge queue
      (!posedge || queue.io.enq.ready) &&
      // And if we're driving an output, if it's been observed
      ((!((enable && !altSyncOccupied) || negedgeRequired)) || clockOut.observed)

    queue.io.deq.ready := false.B
    when(sel.definedUntil >= queue.io.deq.bits) {
      queue.io.deq.ready := true.B
      when(enable && !altSyncOccupied) {
        syncOccupancy := Mux(syncOccupancy === sync.U, sync.U, syncOccupancy + 1.U)
      }.otherwise {
        syncOccupancy := Mux(syncOccupancy === 0.U, 0.U, syncOccupancy - 1.U)
      }
    }

    // On a transition, ensure we enqueue the final output negedge for our domain. 
    val outLatest = Wire(ValidIO(new TimestampedToken(Bool())))
    outLatest.bits.time := iClock.definedUntil
    outLatest.bits.data := Mux(syncOccupancy === sync.U, iClock.valueAtHorizon, false.B)
    outLatest.valid := ((enable && !altSyncOccupied) || negedgeRequired) &&
      (!posedge || queue.io.enq.ready) &&
      (!clockOut.old.valid || clockOut.old.bits.time < iClock.definedUntil)


    when(outLatest.valid && clockOut.observed && posedge) {
      negedgeRequired := true.B
    }.elsewhen(outLatest.valid && clockOut.observed && negedge) {
      negedgeRequired := false.B
    }

    def oldestPosedge = queue.io.deq
  }

  val clockHandlers = for (((iClock, enable), idx) <- clocksIn.zip(selOH).zipWithIndex) yield {
    new PerClockLogic(iClock, enable, idx)
  }

  sel.observed := sel.unchanged || !sel.old.valid ||
    clockHandlers.map {ch => ch.oldestPosedge.valid && (ch.oldestPosedge.bits > sel.definedUntil)}
      .reduce {_ && _}

  val inputsInitialized = clocksIn.foldLeft(true.B)( _ && _.old.valid)
  val inputHorizon = clocksIn.tail.foldLeft(clocksIn.head.old.bits.time) {
    case (a, b) => Mux(a < b.old.bits.time, a, b.old.bits.time)
  }

  val clockHandlerValid = clockHandlers.map(_.outLatest.valid).reduce {_ || _}
  val deadTimeMessageValid  = inputsInitialized && (!clockOut.old.valid || inputHorizon > clockOut.old.bits.time)

  clockOut.latest.valid := clockHandlerValid || deadTimeMessageValid

  when (clockHandlerValid) {
    clockOut.latest.bits := Mux1H(clockHandlers.map { c => (c.outLatest.valid, c.outLatest.bits) })
  }.otherwise {
    clockOut.latest.bits.data := 0.U
    clockOut.latest.bits.time := inputHorizon
  }

  clockOut.old := outOld

  when(clockOut.fire) {
    outOld := clockOut.latest
  }

  // Loosely based off the MutexClockMux in testchipip
  class Reference extends RawModule {
    val clocksIn = IO(Input(Vec(width, Bool())))
    val clockOut = IO(Output(Bool()))
    val sel      = IO(Input(UInt(log2Ceil(width).W)))

    val tuples = clocksIn.map { c =>
      val regs = Seq.fill(sync)({
        val reg = Module(new ReferenceRegister(Bool(), Posedge, init = Some(false.B)))
        // Rely on zero-init for now
        reg.reset := false.B
        reg.clock := c
        reg
      })
      val syncIn = regs.head.d
      val syncOut = regs.tail.foldLeft(syncIn) { case (prevStage: Bool, reg: ReferenceRegister[Bool])  => 
        reg.d := prevStage
        reg.q
      }

      val gateReg = Module(new ReferenceRegister(Bool(), Negedge, init = Some(false.B)))
      gateReg.reset := false.B
      gateReg.clock := c
      gateReg.d := syncOut

      val gatedClock = c && gateReg.q

      (syncIn, !gateReg.q, gatedClock)
    }

    val (_, notClockEns, gatedClocks) = tuples.unzip3
    val enables = UIntToOH(sel).asBools
    for (((syncIn, notClockEn, _), enable) <- tuples.zip(enables)) {
      syncIn := enable && !(notClockEns.filterNot { _ == notClockEn }.reduce {_ || _})
    }

    clockOut := gatedClocks.reduce { _ || _ }
  }
}


object MutexClockMux {
  private[midas] def instantiateAgainstReference[T <: Data](
    clocksIn: Seq[(Bool, TimestampedTuple[Bool])],
    sel: (Bool, TimestampedTuple[Bool])): (Bool, TimestampedTuple[Bool]) = {

    val width = clocksIn.size
    val modelClockMux = Module(new MutexClockMux(width, 3))
    modelClockMux.clocksIn.zip(clocksIn).foreach { case (port, (_, in)) => port <> in }
    modelClockMux.sel <> sel._2

    val refClockMux = Module(new modelClockMux.Reference)
    refClockMux.clocksIn.zip(clocksIn).foreach { case (port, (in, _)) => port <> in }
    refClockMux.sel <> sel._1

    (refClockMux.clockOut, modelClockMux.clockOut)
  }
}

class MutexClockMuxTest(
    inputPeriodPS: Seq[Int],
    selPeriodPS: Int,
    timeout: Int = 50000)(implicit p: Parameters) extends UnitTest(timeout) {

  val clocksIn = inputPeriodPS.map(period => ClockSource.instantiateAgainstReference(period))
  val selTuple = ClockSource.instantiateAgainstReference(selPeriodPS)
  val (reference, model) = MutexClockMux.instantiateAgainstReference(clocksIn, selTuple)
  io.finished := TimestampedTokenTraceEquivalence(reference, model, timeout)
}
