package strober

import midas._
import Chisel._
import cde.{Parameters, Field}
import junctions._

import scala.collection.mutable.{ArrayBuffer, HashSet}
import scala.collection.immutable.ListSet

class SimDecoupledIO[+T <: Data](gen: T)(implicit val p: Parameters) extends Bundle {
  val ready  = Bool(INPUT)
  val valid  = Bool(OUTPUT)
  val target = Decoupled(gen)
  def fire(dummy: Int = 0): Bool = ready && valid
  override def cloneType: this.type = new SimDecoupledIO(gen)(p).asInstanceOf[this.type] 
}

// TODO: Should move to the backend
object SimMemIO {
  def add(mem: NastiIO) {
    val (ins, outs) = SimUtils.parsePorts(mem)
    StroberCompiler.context.memWires ++= ins.unzip._1
    StroberCompiler.context.memWires ++= outs.unzip._1
    StroberCompiler.context.memPorts += mem
  }
  def apply(i: Int): NastiIO = StroberCompiler.context.memPorts(i)
  def apply(wire: Bits) = StroberCompiler.context.memWires(wire)
  def apply(mem: NastiIO) = StroberCompiler.context.memPorts contains mem
  def zipWithIndex = StroberCompiler.context.memPorts.toList.zipWithIndex
  def size = StroberCompiler.context.memPorts.size
}

class MemModelIO(implicit p: Parameters) extends WidgetIO()(p){
  val tNasti = HostPort((new NastiIO), false).flip
  val host_mem =  new NastiIO
}

abstract class MemModel(implicit p: Parameters) extends Widget()(p) {
  val io = new MemModelIO
}

class SimpleLatencyPipe(implicit p: Parameters) extends MemModel {
  val ar_buf = Module(new Queue(new NastiReadAddressChannel,   4, flow=true))
  val aw_buf = Module(new Queue(new NastiWriteAddressChannel,  4, flow=true))
  val w_buf  = Module(new Queue(new NastiWriteDataChannel,    16, flow=true))
  val r_buf  = Module(new Queue(new NastiReadDataChannel,     16, flow=true))
  val b_buf  = Module(new Queue(new NastiWriteResponseChannel, 4, flow=true))

  // Timing Model
  val cycles = RegInit(UInt(0, 64))
  val r_cycles = Module(new Queue(UInt(width=64), 4))
  val w_cycles = Module(new Queue(UInt(width=64), 4))
  val latency = RegInit(UInt(16, 64))
  attach(latency, "LATENCY")

  val tNasti = io.tNasti.hostBits
  val tFire = io.tNasti.hostOut.hostValid && io.tNasti.hostIn.hostReady
  io.tNasti.hostOut.hostReady := tFire
  io.tNasti.hostIn.hostValid := tFire


  when(tFire) { cycles := cycles + UInt(1) }
  r_cycles.io.enq.bits := cycles + latency
  w_cycles.io.enq.bits := cycles + latency
  r_cycles.io.enq.valid := tNasti.ar.fire() && tFire
  w_cycles.io.enq.valid := tNasti.w.fire()  && tNasti.w.bits.last && tFire
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
  io.host_mem.ar <> ar_buf.io.deq
  io.host_mem.w  <> w_buf.io.deq

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
