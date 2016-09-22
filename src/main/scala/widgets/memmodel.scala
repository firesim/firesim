package strober

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

object SimMemIO {
  private val mems = ArrayBuffer[NastiIO]()
  private val wires = HashSet[Bits]()
  def add(mem: NastiIO) {
    val (ins, outs) = SimUtils.parsePorts(mem)
    wires ++= ins.unzip._1
    wires ++= outs.unzip._1
    mems += mem
  }
  def apply(i: Int): NastiIO = mems(i)
  def apply(mem: NastiIO) = mems contains mem
  def apply(wire: Bits) = wires(wire)
  def size = mems.size
  def zipWithIndex = mems.toList.zipWithIndex
}

class SimMemIO(implicit p: Parameters) extends NastiBundle()(p) {
  val aw = new SimDecoupledIO(new NastiWriteAddressChannel)
  val w  = new SimDecoupledIO(new NastiWriteDataChannel)
  val b  = new SimDecoupledIO(new NastiWriteResponseChannel).flip
  val ar = new SimDecoupledIO(new NastiReadAddressChannel)
  val r  = new SimDecoupledIO(new NastiReadDataChannel).flip
}

class MemModelIO(implicit p: Parameters) extends WidgetIO()(p){
  val sim_mem  = (new SimMemIO).flip
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
  val r_ready = Mux(!latency.orR, !io.sim_mem.ar.target.valid,
    !(r_cycles.io.deq.valid && r_cycles.io.deq.bits <= cycles)) || r_buf.io.deq.valid
  val w_ready = Mux(!latency.orR, !(io.sim_mem.w.target.valid && io.sim_mem.w.target.bits.last),
    !(w_cycles.io.deq.valid && w_cycles.io.deq.bits <= cycles)) || b_buf.io.deq.valid
  val reqValid = io.sim_mem.ar.valid && io.sim_mem.aw.valid && io.sim_mem.w.valid
  val respReady = io.sim_mem.r.ready && io.sim_mem.b.ready
  val fire = reqValid && respReady && r_ready && w_ready
  when(fire) { cycles := cycles + UInt(1) }
  r_cycles.io.enq.bits := cycles + latency
  w_cycles.io.enq.bits := cycles + latency
  r_cycles.io.enq.valid := io.sim_mem.ar.target.fire() && fire && latency.orR
  w_cycles.io.enq.valid := io.sim_mem.w.target.fire()  && io.sim_mem.w.target.bits.last && fire && latency.orR
  r_cycles.io.deq.ready := io.sim_mem.r.target.fire()  && io.sim_mem.r.target.bits.last && fire && latency.orR
  w_cycles.io.deq.ready := io.sim_mem.b.target.fire()  && fire && latency.orR

  // Requests
  io.sim_mem.ar.ready := fire
  io.sim_mem.aw.ready := fire
  io.sim_mem.w.ready  := fire
  io.sim_mem.ar.target.ready := ar_buf.io.enq.ready && r_cycles.io.enq.ready
  io.sim_mem.aw.target.ready := aw_buf.io.enq.ready && w_cycles.io.enq.ready
  io.sim_mem.w.target.ready  := w_buf.io.enq.ready  && w_cycles.io.enq.ready
  ar_buf.io.enq.valid := io.sim_mem.ar.target.fire() && fire
  aw_buf.io.enq.valid := io.sim_mem.aw.target.fire() && fire
  w_buf.io.enq.valid  := io.sim_mem.w.target.fire()  && fire
  ar_buf.io.enq.bits  := io.sim_mem.ar.target.bits
  aw_buf.io.enq.bits  := io.sim_mem.aw.target.bits
  w_buf.io.enq.bits   := io.sim_mem.w.target.bits
  io.host_mem.aw <> aw_buf.io.deq
  io.host_mem.ar <> ar_buf.io.deq
  io.host_mem.w  <> w_buf.io.deq

  // Response
  io.sim_mem.b.valid := fire
  io.sim_mem.r.valid := fire
  io.sim_mem.r.target.bits <> r_buf.io.deq.bits
  io.sim_mem.b.target.bits <> b_buf.io.deq.bits
  io.sim_mem.r.target.valid := r_buf.io.deq.valid && r_cycles.io.deq.bits <= cycles && r_cycles.io.deq.valid
  io.sim_mem.b.target.valid := b_buf.io.deq.valid && w_cycles.io.deq.bits <= cycles && w_cycles.io.deq.valid
  r_buf.io.deq.ready := io.sim_mem.r.target.fire() && fire 
  b_buf.io.deq.ready := io.sim_mem.b.target.fire() && fire
  r_buf.io.enq <> io.host_mem.r
  b_buf.io.enq <> io.host_mem.b

  // Connect all programmable registers to the control interrconect
  genCRFile()
}
