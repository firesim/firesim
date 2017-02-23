package midas
package widgets

import chisel3._
import chisel3.util._
import junctions._
import config.{Parameters, Field}

class SimpleLatencyPipe(implicit val p: Parameters) extends NastiWidgetBase {
  // Timing Model
  val rCycles = Module(new Queue(UInt(64.W), 4))
  val wCycles = Module(new Queue(UInt(64.W), 4))
  val rCycleValid = Wire(Bool())
  val wCycleValid = Wire(Bool())
  val latency = RegInit(16.U(32.W))

  val stall = (rCycleValid && !rBuf.io.deq.valid) || (wCycleValid && !bBuf.io.deq.valid)
  val (fire, cycles, targetReset) = elaborate(
    stall, rCycleValid, wCycleValid, rCycles.io.enq.ready, wCycles.io.enq.ready)

  rCycles.reset := reset || targetReset
  wCycles.reset := reset || targetReset
  rCycleValid := rCycles.io.deq.valid && rCycles.io.deq.bits <= cycles
  wCycleValid := wCycles.io.deq.valid && wCycles.io.deq.bits <= cycles
  rCycles.io.enq.bits  := cycles + latency
  wCycles.io.enq.bits  := cycles + latency
  rCycles.io.enq.valid := tNasti.ar.fire() && fire
  wCycles.io.enq.valid := tNasti.w.fire()  && tNasti.w.bits.last && fire
  rCycles.io.deq.ready := tNasti.r.fire()  && tNasti.r.bits.last && fire
  wCycles.io.deq.ready := tNasti.b.fire()  && fire

  io.host_mem.aw <> awBuf.io.deq
  io.host_mem.ar <> arBuf.io.deq
  io.host_mem.w  <> wBuf.io.deq
  rBuf.io.enq <> io.host_mem.r
  bBuf.io.enq <> io.host_mem.b

  // Connect all programmable registers to the control interrconect
  attach(latency, "LATENCY")
  genCRFile()
}
