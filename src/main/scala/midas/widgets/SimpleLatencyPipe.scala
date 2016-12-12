package midas
package widgets

import chisel3._
import chisel3.util._
import junctions._
import cde.{Parameters, Field}

class SimpleLatencyPipe(implicit p: Parameters) extends NastiWidgetBase()(p) {
  // Timing Model
  val rCycles = Module(new Queue(UInt(width=64), 4))
  val wCycles = Module(new Queue(UInt(width=64), 4))
  val rCycleFire = Wire(Bool())
  val wCycleFire = Wire(Bool())
  val latency = RegInit(UInt(16, 32))

  val tNasti = io.tNasti.hBits
  val targetFire = io.tNasti.toHost.hValid && io.tNasti.fromHost.hReady && io.tReset.valid
  val memStall = (rCycleFire && !rBuf.io.deq.valid) || (wCycleFire && !bBuf.io.deq.valid)
  val memFire = targetFire && !memStall
  val (targetReset, cycles) = connect(memFire)

  rCycleFire := rCycles.io.deq.valid && rCycles.io.deq.bits <= cycles
  wCycleFire := wCycles.io.deq.valid && wCycles.io.deq.bits <= cycles
  rCycles.io.enq.bits := cycles + latency
  wCycles.io.enq.bits := cycles + latency
  rCycles.io.enq.valid := tNasti.ar.fire() && memFire
  wCycles.io.enq.valid := tNasti.w.fire()  && tNasti.w.bits.last && memFire
  rCycles.io.deq.ready := tNasti.r.fire()  && tNasti.r.bits.last && memFire
  wCycles.io.deq.ready := tNasti.b.fire()  && memFire
  rCycles.reset := reset || targetReset
  wCycles.reset := reset || targetReset

  // Requests
  tNasti.ar.ready := arBuf.io.enq.ready && rCycles.io.enq.ready
  tNasti.aw.ready := awBuf.io.enq.ready && wCycles.io.enq.ready
  tNasti.w.ready  := wBuf.io.enq.ready  && wCycles.io.enq.ready
  arBuf.io.enq.valid := tNasti.ar.valid && memFire && !io.tReset.bits
  awBuf.io.enq.valid := tNasti.aw.valid && memFire && !io.tReset.bits
  wBuf.io.enq.valid  := tNasti.w.valid  && memFire && !io.tReset.bits
  arBuf.io.enq.bits  := tNasti.ar.bits
  awBuf.io.enq.bits  := tNasti.aw.bits
  wBuf.io.enq.bits   := tNasti.w.bits
  io.host_mem.aw <> awBuf.io.deq
  io.host_mem.ar <> arBuf.io.deq
  io.host_mem.w  <> wBuf.io.deq

  // Response
  tNasti.r.bits <> rBuf.io.deq.bits
  tNasti.b.bits <> bBuf.io.deq.bits
  tNasti.r.valid := rBuf.io.deq.valid && rCycleFire
  tNasti.b.valid := bBuf.io.deq.valid && wCycleFire
  rBuf.io.deq.ready := tNasti.r.ready && memFire && rCycleFire
  bBuf.io.deq.ready := tNasti.b.ready && memFire && wCycleFire
  rBuf.io.enq <> io.host_mem.r
  bBuf.io.enq <> io.host_mem.b

  // Connect all programmable registers to the control interrconect
  attach(latency, "LATENCY")
  genCRFile()
}
