package strober

import Chisel._
import cde.{Parameters, Field}
import junctions._

import scala.collection.mutable.{ArrayBuffer, HashSet}
import scala.collection.immutable.ListSet

case object MemMaxCycles extends Field[Int]

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
  def apply(mem: NastiIO) {
    val (ins, outs) = SimUtils.parsePorts(mem)
    wires ++= ins.unzip._1
    wires ++= outs.unzip._1
    mems += mem 
  }
  def apply(i: Int): NastiIO = mems(i)
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

  def <>(mIo: NastiIO, wIo: SimWrapperIO) {
    // Target Connection
    def targetConnect[T <: Bits](target: T, wire: T) = wire match {
      /* case _: Bool if wire.dir == OUTPUT =>  target := wIo.getOuts(wire).head.bits
      case _: Bool if wire.dir == INPUT => wIo.getIns(wire).head.bits := target
      case _ if wire.dir == OUTPUT => target := Vec(wIo.getOuts(wire) map (_.bits)).toBits
      case _ if wire.dir == INPUT => wIo.getIns(wire).zipWithIndex foreach {
        case (in, i) => in.bits := target.toUInt >> UInt(i*wIo.channelWidth) } */
      case _ =>
    }
    targetConnect(aw.target.bits.id,     mIo.aw.bits.id)
    targetConnect(aw.target.bits.addr,   mIo.aw.bits.addr)
    targetConnect(aw.target.bits.len,    mIo.aw.bits.len)
    targetConnect(aw.target.bits.size,   mIo.aw.bits.size)
    targetConnect(aw.target.bits.burst,  mIo.aw.bits.burst)
    targetConnect(aw.target.bits.lock,   mIo.aw.bits.lock)
    targetConnect(aw.target.bits.cache,  mIo.aw.bits.cache)
    targetConnect(aw.target.bits.prot,   mIo.aw.bits.prot)
    targetConnect(aw.target.bits.qos,    mIo.aw.bits.qos)
    targetConnect(aw.target.bits.region, mIo.aw.bits.region)
    targetConnect(aw.target.valid,       mIo.aw.valid)
    targetConnect(aw.target.ready,       mIo.aw.ready)
    
    targetConnect(ar.target.bits.id,     mIo.ar.bits.id)
    targetConnect(ar.target.bits.addr,   mIo.ar.bits.addr)
    targetConnect(ar.target.bits.len,    mIo.ar.bits.len)
    targetConnect(ar.target.bits.size,   mIo.ar.bits.size)
    targetConnect(ar.target.bits.burst,  mIo.ar.bits.burst)
    targetConnect(ar.target.bits.lock,   mIo.ar.bits.lock)
    targetConnect(ar.target.bits.cache,  mIo.ar.bits.cache)
    targetConnect(ar.target.bits.prot,   mIo.ar.bits.prot)
    targetConnect(ar.target.bits.qos,    mIo.ar.bits.qos)
    targetConnect(ar.target.bits.region, mIo.ar.bits.region)
    targetConnect(ar.target.bits.user,   mIo.ar.bits.user)
    targetConnect(ar.target.valid,       mIo.ar.valid)
    targetConnect(ar.target.ready,       mIo.ar.ready)

    targetConnect(w.target.bits.strb,    mIo.w.bits.strb)
    targetConnect(w.target.bits.data,    mIo.w.bits.data)
    targetConnect(w.target.bits.last,    mIo.w.bits.last)
    targetConnect(w.target.bits.user,    mIo.w.bits.user)
    targetConnect(w.target.valid,        mIo.w.valid)
    targetConnect(w.target.ready,        mIo.w.ready)

    targetConnect(r.target.bits.id,      mIo.r.bits.id)
    targetConnect(r.target.bits.data,    mIo.r.bits.data)
    targetConnect(r.target.bits.last,    mIo.r.bits.last)
    targetConnect(r.target.bits.resp,    mIo.r.bits.resp)
    targetConnect(r.target.bits.user,    mIo.r.bits.user)
    targetConnect(r.target.valid,        mIo.r.valid)
    targetConnect(r.target.ready,        mIo.r.ready)

    targetConnect(b.target.bits.id,      mIo.b.bits.id)
    targetConnect(b.target.bits.resp,    mIo.b.bits.resp)
    targetConnect(b.target.bits.user,    mIo.b.bits.user)
    targetConnect(b.target.valid,        mIo.b.valid)
    targetConnect(b.target.ready,        mIo.b.ready)

    // Host Connection
    /*
    def hostConnect(res: Bool, arg: (String, Bits), ready: Bool) = arg match { 
      case (_, wire) if wire.dir == INPUT =>
        val ins = wIo.getIns(wire)
        ins foreach (_.valid := ready)
        (ins foldLeft res)(_ && _.ready)
      case (_, wire) if wire.dir == OUTPUT =>
        val outs = wIo.getOuts(wire)
        outs foreach (_.ready := ready)
        (outs foldLeft res)(_ && _.valid)
    }
    aw.valid := (mIo.aw.flatten foldLeft Bool(true))(hostConnect(_, _, aw.ready))
    ar.valid := (mIo.ar.flatten foldLeft Bool(true))(hostConnect(_, _, ar.ready))
    w.valid  := (mIo.w.flatten  foldLeft Bool(true))(hostConnect(_, _,  w.ready))
    r.ready  := (mIo.r.flatten  foldLeft Bool(true))(hostConnect(_, _,  r.valid))
    b.ready  := (mIo.b.flatten  foldLeft Bool(true))(hostConnect(_, _,  b.valid)) */
  }
}

class ChannelMemIOConverter(implicit p: Parameters) extends NastiModule()(p) {
  val maxLatency = p(MemMaxCycles)
  val maxLatencyWidth = log2Up(maxLatency+1)
  val io = new Bundle {
    val sim_mem  = (new SimMemIO).flip
    val host_mem =  new NastiIO
    val latency  = Decoupled(UInt(width=maxLatencyWidth)).flip
  }
  val aw = new SimDecoupledIO(new NastiWriteAddressChannel)
  val w  = new SimDecoupledIO(new NastiWriteDataChannel)
  val b  = new SimDecoupledIO(new NastiWriteResponseChannel).flip
  val ar = new SimDecoupledIO(new NastiReadAddressChannel)
  val r  = new SimDecoupledIO(new NastiReadDataChannel).flip

  val aw_buf  = Module(new Queue(new NastiWriteAddressChannel,  4,  flow=true))
  val ar_buf  = Module(new Queue(new NastiReadAddressChannel,   4,  flow=true))
  val w_buf   = Module(new Queue(new NastiWriteDataChannel,     16, flow=true))
  val r_buf   = Module(new Queue(new NastiReadDataChannel,      32, flow=true))
  val b_buf   = Module(new Queue(new NastiWriteResponseChannel, 4,  flow=true))
  val r_pipe  = Module(new HellaQueue(maxLatency)(Valid(new NastiReadDataChannel)))
  val latency = RegInit(UInt(0, maxLatencyWidth))
  val counter = RegInit(UInt(0, maxLatencyWidth))

  io.latency.ready := latency === counter
  when (io.latency.fire()) { 
    latency := io.latency.bits
    counter := UInt(0)
  }.elsewhen (r_pipe.io.enq.fire() && counter =/= latency) {
    counter := counter + UInt(1)
  }
  r_pipe.reset := reset || io.latency.fire()

  val pipe_rdy = counter === latency || !latency.orR
  io.sim_mem.aw.target.ready := aw_buf.io.enq.ready && w_buf.io.enq.ready
  io.sim_mem.aw.ready        := io.sim_mem.aw.valid && pipe_rdy
  aw_buf.io.enq.bits.id      := io.sim_mem.aw.target.bits.id
  aw_buf.io.enq.bits.addr    := io.sim_mem.aw.target.bits.addr
  aw_buf.io.enq.bits.len     := io.sim_mem.aw.target.bits.len
  aw_buf.io.enq.bits.size    := io.sim_mem.aw.target.bits.size
  aw_buf.io.enq.bits.burst   := io.sim_mem.aw.target.bits.burst
  aw_buf.io.enq.bits.lock    := io.sim_mem.aw.target.bits.lock
  aw_buf.io.enq.bits.cache   := io.sim_mem.aw.target.bits.cache
  aw_buf.io.enq.bits.prot    := io.sim_mem.aw.target.bits.prot
  aw_buf.io.enq.bits.qos     := io.sim_mem.aw.target.bits.qos
  aw_buf.io.enq.bits.region  := io.sim_mem.aw.target.bits.region
  aw_buf.io.enq.bits.user    := io.sim_mem.aw.target.bits.user
  aw_buf.io.enq.valid        := io.sim_mem.aw.target.fire() && io.sim_mem.aw.ready

  io.sim_mem.ar.target.ready := ar_buf.io.enq.ready && r_buf.io.enq.ready
  io.sim_mem.ar.ready        := io.sim_mem.ar.valid && pipe_rdy
  ar_buf.io.enq.bits.id      := io.sim_mem.ar.target.bits.id
  ar_buf.io.enq.bits.addr    := io.sim_mem.ar.target.bits.addr
  ar_buf.io.enq.bits.len     := io.sim_mem.ar.target.bits.len
  ar_buf.io.enq.bits.size    := io.sim_mem.ar.target.bits.size
  ar_buf.io.enq.bits.burst   := io.sim_mem.ar.target.bits.burst
  ar_buf.io.enq.bits.lock    := io.sim_mem.ar.target.bits.lock
  ar_buf.io.enq.bits.cache   := io.sim_mem.ar.target.bits.cache
  ar_buf.io.enq.bits.prot    := io.sim_mem.ar.target.bits.prot
  ar_buf.io.enq.bits.qos     := io.sim_mem.ar.target.bits.qos
  ar_buf.io.enq.bits.region  := io.sim_mem.ar.target.bits.region
  ar_buf.io.enq.bits.user    := io.sim_mem.ar.target.bits.user
  ar_buf.io.enq.valid        := io.sim_mem.ar.target.fire() && io.sim_mem.ar.ready

  io.sim_mem.w.target.ready  := w_buf.io.enq.ready
  io.sim_mem.w.ready         := io.sim_mem.w.valid && io.sim_mem.r.valid // don't write memory before read!
  w_buf.io.enq.bits.strb     := io.sim_mem.w.target.bits.strb
  w_buf.io.enq.bits.data     := io.sim_mem.w.target.bits.data
  w_buf.io.enq.bits.last     := io.sim_mem.w.target.bits.last
  w_buf.io.enq.bits.user     := io.sim_mem.w.target.bits.user
  w_buf.io.enq.valid         := io.sim_mem.w.target.fire() && io.sim_mem.w.ready

  io.sim_mem.b.valid := io.sim_mem.b.ready && pipe_rdy && (b_buf.io.deq.valid ||
    io.sim_mem.w.ready && !(io.sim_mem.w.target.fire() && io.sim_mem.w.target.bits.last))
  io.sim_mem.b.target.valid     := b_buf.io.deq.valid
  io.sim_mem.b.target.bits.id   := b_buf.io.deq.bits.id
  io.sim_mem.b.target.bits.resp := b_buf.io.deq.bits.resp
  io.sim_mem.b.target.bits.user := b_buf.io.deq.bits.user
  b_buf.io.deq.ready := io.sim_mem.b.valid && io.sim_mem.b.target.ready

  io.sim_mem.r.valid := io.sim_mem.r.ready && pipe_rdy && (
    io.sim_mem.ar.ready && !io.sim_mem.ar.target.fire() ||
    Mux(latency.orR, r_buf.io.enq.valid || r_pipe.io.deq.bits.valid, r_buf.io.deq.valid))
  io.sim_mem.r.target.valid     := Mux(latency.orR, r_pipe.io.deq.bits.valid, r_buf.io.deq.valid)
  io.sim_mem.r.target.bits.id   := Mux(latency.orR, r_pipe.io.deq.bits.bits.id, r_buf.io.deq.bits.id)
  io.sim_mem.r.target.bits.data := Mux(latency.orR, r_pipe.io.deq.bits.bits.data, r_buf.io.deq.bits.data)
  io.sim_mem.r.target.bits.last := Mux(latency.orR, r_pipe.io.deq.bits.bits.last, r_buf.io.deq.bits.last)
  io.sim_mem.r.target.bits.resp := Mux(latency.orR, r_pipe.io.deq.bits.bits.resp, r_buf.io.deq.bits.resp)
  io.sim_mem.r.target.bits.user := Mux(latency.orR, r_pipe.io.deq.bits.bits.user, r_buf.io.deq.bits.user)

  r_buf.io.deq.ready  := Mux(latency.orR, r_pipe.io.deq.ready, io.sim_mem.r.valid && io.sim_mem.r.target.ready)
  r_pipe.io.deq.ready := io.sim_mem.r.valid && (io.sim_mem.r.target.ready || !io.sim_mem.r.target.valid)
  r_pipe.io.enq.valid := r_pipe.io.deq.ready || latency =/= counter
  r_pipe.io.enq.bits.valid      := r_buf.io.deq.valid
  r_pipe.io.enq.bits.bits.id    := r_buf.io.deq.bits.id
  r_pipe.io.enq.bits.bits.data  := r_buf.io.deq.bits.data
  r_pipe.io.enq.bits.bits.last  := r_buf.io.deq.bits.last
  r_pipe.io.enq.bits.bits.resp  := r_buf.io.deq.bits.resp
  r_pipe.io.enq.bits.bits.user  := r_buf.io.deq.bits.user

  io.host_mem.aw <> aw_buf.io.deq
  io.host_mem.ar <> ar_buf.io.deq
  io.host_mem.w  <> w_buf.io.deq
  b_buf.io.enq   <> io.host_mem.b
  r_buf.io.enq   <> io.host_mem.r
}
