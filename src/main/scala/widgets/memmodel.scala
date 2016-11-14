package midas_widgets

import chisel3._
import chisel3.util._
import junctions._
import cde.{Parameters, Field}

import scala.collection.mutable.{ArrayBuffer, HashSet}
import scala.collection.immutable.ListSet

abstract class MemModelConfig // TODO: delete it
case object MemModelKey extends Field[Option[Parameters => MemModel]]

class SimDecoupledIO[+T <: Data](gen: T)(implicit val p: Parameters) extends Bundle {
  val ready  = Bool(INPUT)
  val valid  = Bool(OUTPUT)
  val target = Decoupled(gen)
  def fire(dummy: Int = 0): Bool = ready && valid
  override def cloneType: this.type = new SimDecoupledIO(gen)(p).asInstanceOf[this.type] 
}

class MemModelIO(implicit p: Parameters) extends WidgetIO()(p){
  val tNasti = Flipped(HostPort((new NastiIO), false))
  val tReset = Bool(INPUT)
  val host_mem = new NastiIO
}

abstract class MemModel(implicit p: Parameters) extends Widget()(p){
  val io = IO(new MemModelIO)
}

class SimpleLatencyPipe(implicit p: Parameters) extends MemModel {
  val tNasti = io.tNasti.hBits
  val tFire = io.tNasti.toHost.hValid && io.tNasti.fromHost.hReady

  val ar_buf = Module(new Queue(new NastiReadAddressChannel,   4, flow=true))
  val aw_buf = Module(new Queue(new NastiWriteAddressChannel,  4, flow=true))
  val w_buf  = Module(new Queue(new NastiWriteDataChannel,    16, flow=true))
  val r_buf  = Module(new Queue(new NastiReadDataChannel,     16, flow=true))
  val b_buf  = Module(new Queue(new NastiWriteResponseChannel, 4, flow=true))

  // Bad assumption: We have no outstanding read or write requests to host
  // during target reset. This will be handled properly in the fully fledged
  // memory model; i'm too lazy to properly handle this here.
  val targetReset = tFire && io.tReset
  ar_buf.reset := reset || targetReset
  aw_buf.reset := reset || targetReset
  r_buf.reset := reset || targetReset
  b_buf.reset := reset || targetReset
  w_buf.reset := reset || targetReset


  // Timing Model
  val cycles = RegInit(UInt(0, 64))
  val r_cycles = Module(new Queue(UInt(width=64), 4))
  val w_cycles = Module(new Queue(UInt(width=64), 4))
  val latency = RegInit(UInt(16, 32))
  attach(latency, "LATENCY")

  io.tNasti.toHost.hReady := tFire
  io.tNasti.fromHost.hValid := tFire

  when(tFire) { cycles := cycles + UInt(1) }
  r_cycles.io.enq.bits := cycles + latency
  w_cycles.io.enq.bits := cycles + latency
  r_cycles.io.enq.valid := tNasti.ar.fire() && tFire && ~io.tReset
  w_cycles.io.enq.valid := tNasti.w.fire()  && tNasti.w.bits.last && tFire && ~io.tReset
  r_cycles.io.deq.ready := tNasti.r.fire()  && tNasti.r.bits.last && tFire
  w_cycles.io.deq.ready := tNasti.b.fire()  && tFire

  // Requests
  tNasti.ar.ready := ar_buf.io.enq.ready && r_cycles.io.enq.ready
  tNasti.aw.ready := aw_buf.io.enq.ready && w_cycles.io.enq.ready
  tNasti.w.ready  := w_buf.io.enq.ready  && w_cycles.io.enq.ready
  ar_buf.io.enq.valid := tNasti.ar.valid && tFire
  aw_buf.io.enq.valid := tNasti.aw.valid && tFire
  w_buf.io.enq.valid  := tNasti.w.valid  && tFire
  ar_buf.io.enq.bits  := tNasti.ar.bits
  aw_buf.io.enq.bits  := tNasti.aw.bits
  w_buf.io.enq.bits   := tNasti.w.bits
  io.host_mem.aw <> aw_buf.io.deq
  io.host_mem.aw.valid := aw_buf.io.deq.valid && ~targetReset
  io.host_mem.ar <> ar_buf.io.deq
  io.host_mem.ar.valid := ar_buf.io.deq.valid && ~targetReset
  io.host_mem.w  <> w_buf.io.deq
  io.host_mem.w.valid := w_buf.io.deq.valid && ~targetReset

  // Response
  tNasti.r.bits <> r_buf.io.deq.bits
  tNasti.b.bits <> b_buf.io.deq.bits
  tNasti.r.valid := r_buf.io.deq.valid && (r_cycles.io.deq.bits <= cycles) && r_cycles.io.deq.valid
  tNasti.b.valid := b_buf.io.deq.valid && (w_cycles.io.deq.bits <= cycles) && w_cycles.io.deq.valid
  r_buf.io.deq.ready := tNasti.r.ready && tFire && (r_cycles.io.deq.bits <= cycles) && r_cycles.io.deq.valid
  b_buf.io.deq.ready := tNasti.b.ready && tFire && (w_cycles.io.deq.bits <= cycles) && w_cycles.io.deq.valid

  r_buf.io.enq <> io.host_mem.r
  b_buf.io.enq <> io.host_mem.b

  // Connect all programmable registers to the control interrconect
  genCRFile()
}
