package strober

import Chisel._
import junctions._

import scala.collection.mutable.ArrayBuffer
import scala.collection.immutable.ListSet

class SimDecoupledIO[+T <: Data](gen: T) extends Bundle {
  val ready  = Bool(INPUT)
  val valid  = Bool(OUTPUT)
  val target = Decoupled(gen)
  def fire(dummy: Int = 0): Bool = ready && valid
  override def cloneType: this.type = new SimDecoupledIO(gen).asInstanceOf[this.type] 
}

object SimMemIO {
  private val mems = ArrayBuffer[MemIO]()
  def apply(mem: MemIO) { mems += mem }
  def apply(i: Int) = mems(i)
  def size = mems.size
  def contains(wire: Bits) = mems exists (_.flatten.unzip._2 contains wire) 
  def zipWithIndex = mems.toList.zipWithIndex
}

class SimMemIO extends MIFBundle {
  val req_cmd  = new SimDecoupledIO(new MemReqCmd)
  val req_data = new SimDecoupledIO(new MemData)
  val resp     = (new SimDecoupledIO(new MemResp)).flip

  def <>(mIo: MemIO, wIo: SimWrapperIO) {
    // Target Connection
    req_cmd.target.bits.addr := wIo.outMap(mIo.req_cmd.bits.addr).bits.data
    req_cmd.target.bits.tag  := wIo.outMap(mIo.req_cmd.bits.tag).bits.data
    req_cmd.target.bits.rw   := wIo.outMap(mIo.req_cmd.bits.rw).bits.data
    req_cmd.target.valid     := wIo.outMap(mIo.req_cmd.valid).bits.data
    wIo.inMap(mIo.req_cmd.ready).bits.data := req_cmd.target.ready
    
    req_data.target.bits.data := wIo.outMap(mIo.req_data.bits.data).bits.data
    req_data.target.valid     := wIo.outMap(mIo.req_data.valid).bits.data
    wIo.inMap(mIo.req_data.ready).bits.data := req_data.target.ready

    wIo.inMap(mIo.resp.bits.data).bits.data := resp.target.bits.data
    wIo.inMap(mIo.resp.bits.tag).bits.data  := resp.target.bits.tag
    wIo.inMap(mIo.resp.valid).bits.data     := resp.target.valid
    resp.target.ready := wIo.outMap(mIo.resp.ready).bits.data

    // Host Connection
    req_cmd.valid := (mIo.req_cmd.flatten foldLeft Bool(true)){
      case (res, (_, wire)) if wire.dir == INPUT =>
        wIo.inMap(wire).valid := req_cmd.ready
        wIo.inMap(wire).ready && res
      case (res, (_, wire)) if wire.dir == OUTPUT =>
        wIo.outMap(wire).ready := req_cmd.ready
        wIo.outMap(wire).valid && res
    }

    req_data.valid := (mIo.req_data.flatten foldLeft Bool(true)){
      case (res, (_, wire)) if wire.dir == INPUT =>
        wIo.inMap(wire).valid := req_data.ready
        wIo.inMap(wire).ready && res
      case (res, (_, wire)) if wire.dir == OUTPUT =>
        wIo.outMap(wire).ready := req_data.ready
        wIo.outMap(wire).valid && res
    }

    resp.ready := (mIo.resp.flatten foldLeft Bool(true)){
      case (res, (_, wire)) if wire.dir == INPUT =>
        wIo.inMap(wire).valid := resp.valid
        wIo.inMap(wire).ready && res
      case (res, (_, wire)) if wire.dir == OUTPUT =>
        wIo.outMap(wire).ready := resp.valid
        wIo.outMap(wire).valid && res
    }
  }
}

class ChannelMemIOConverter extends MIFModule {
  val io = new Bundle {
    val sim_mem  = (new SimMemIO).flip
    val host_mem =  new MemIO
  }

  val req_cmd_buf  = Module(new Queue(new MemReqCmd, 2))
  val req_data_buf = Module(new Queue(new MemData, 2))
  val resp_buf     = Module(new Queue(new MemResp, 2))
  
  io.sim_mem.req_cmd.target.ready := Bool(true)
  io.sim_mem.req_cmd.ready     := io.sim_mem.req_cmd.valid && req_cmd_buf.io.enq.ready &&
                                  io.sim_mem.req_data.valid && req_data_buf.io.enq.ready
  req_cmd_buf.io.enq.bits.addr := io.sim_mem.req_cmd.target.bits.addr
  req_cmd_buf.io.enq.bits.tag  := io.sim_mem.req_cmd.target.bits.tag
  req_cmd_buf.io.enq.bits.rw   := io.sim_mem.req_cmd.target.bits.rw
  req_cmd_buf.io.enq.valid     := io.sim_mem.req_cmd.target.valid && io.sim_mem.req_cmd.ready

  io.sim_mem.req_data.target.ready := Bool(true)
  io.sim_mem.req_data.ready     := io.sim_mem.req_cmd.ready
  req_data_buf.io.enq.bits.data := io.sim_mem.req_data.target.bits.data
  req_data_buf.io.enq.valid     := io.sim_mem.req_data.target.valid && io.sim_mem.req_data.ready

  resp_buf.io.deq.ready := io.sim_mem.resp.ready && io.sim_mem.resp.target.ready 
  io.sim_mem.resp.valid := io.sim_mem.resp.ready && (resp_buf.io.deq.valid || 
    io.sim_mem.req_cmd.ready && (!io.sim_mem.req_cmd.target.valid || io.sim_mem.req_cmd.target.bits.rw))
  io.sim_mem.resp.target.bits.data := resp_buf.io.deq.bits.data
  io.sim_mem.resp.target.bits.tag  := resp_buf.io.deq.bits.tag
  io.sim_mem.resp.target.valid     := resp_buf.io.deq.valid

  io.host_mem.req_cmd  <> req_cmd_buf.io.deq
  io.host_mem.req_data <> req_data_buf.io.deq
  resp_buf.io.enq      <> io.host_mem.resp
}

object NASTI_MemIO_ConverterIO {
  // rAddrOffset + 0 : req_cmd_addr
  // rAddrOffset + 1 : {req_cmd_tag, req_cmd_rw}
  // rAddrOffset + 2 : req_cmd_data
  // wAddrOffset + 0 : resp_data
  // wAddrOffset + 1 : resp_tag
  val inNum = 3
  val outNum = 2
}

class NASTI_MemIO_ConverterIO extends NASTIBundle {
  val inNum = NASTI_MemIO_ConverterIO.inNum
  val outNum = NASTI_MemIO_ConverterIO.outNum

  val ins = Vec.fill(inNum){Decoupled(UInt(width=nastiXDataBits)).flip}
  val in_addr = UInt(INPUT, nastiXAddrBits)
  val outs = Vec.fill(outNum){Decoupled(UInt(width=nastiXDataBits))}
  val out_addr = UInt(INPUT, nastiXAddrBits)
  val mem = new MemIO
}

class NASTI_MemIOConverter(rAddrOffset: Int, wAddrOffset: Int) extends MIFModule {
  val io = new NASTI_MemIO_ConverterIO
  val req_cmd_addr = Module(new NASTI2Input(UInt(width=mifAddrBits),  rAddrOffset))
  val req_cmd_tag  = Module(new NASTI2Input(UInt(width=mifTagBits+1), rAddrOffset+1))
  val req_data     = Module(new NASTI2Input(UInt(width=mifDataBits),  rAddrOffset+2))
  val resp_data    = Module(new Output2NASTI(UInt(width=mifDataBits), wAddrOffset))
  val resp_tag     = Module(new Output2NASTI(UInt(width=mifTagBits),  wAddrOffset+1))
  val req_cmd_buf  = Module(new Queue(new MemReqCmd, 2))
  val req_data_buf = Module(new Queue(new MemData, 2))
  val resp_buf     = Module(new Queue(new MemResp, 2))
  val resp_data_in_ready = RegInit(Bool(false))
  val resp_tag_in_ready  = RegInit(Bool(false))

  // input to converters
  req_cmd_addr.io.in <> io.ins(0)
  req_cmd_addr.io.addr := io.in_addr
  req_cmd_tag.io.in <> io.ins(1)
  req_cmd_tag.io.addr := io.in_addr
  req_data.io.in <> io.ins(2)
  req_data.io.addr := io.in_addr

  // converters to buffers
  req_cmd_buf.io.enq.bits.addr := req_cmd_addr.io.out.bits
  req_cmd_buf.io.enq.bits.tag := req_cmd_tag.io.out.bits >> UInt(1)
  req_cmd_buf.io.enq.bits.rw := req_cmd_tag.io.out.bits(0)
  req_cmd_buf.io.enq.valid := req_cmd_addr.io.out.valid && req_cmd_tag.io.out.valid
  req_cmd_addr.io.out.ready := req_cmd_buf.io.enq.fire()
  req_cmd_tag.io.out.ready := req_cmd_buf.io.enq.fire()
  req_data_buf.io.enq.bits.data := req_data.io.out.bits
  req_data_buf.io.enq.valid := req_data.io.out.valid
  req_data.io.out.ready := req_data_buf.io.enq.ready
  
  // buffers to mem
  req_cmd_buf.io.deq <> io.mem.req_cmd
  req_data_buf.io.deq <> io.mem.req_data

  // mem to buffer
  io.mem.resp <> resp_buf.io.enq

  // buffer to converters
  resp_data.io.addr := io.out_addr
  resp_data.io.in.bits := resp_buf.io.deq.bits.data
  resp_data.io.in.valid := resp_buf.io.deq.valid
  resp_tag.io.addr := io.out_addr
  resp_tag.io.in.bits := resp_buf.io.deq.bits.tag
  resp_tag.io.in.valid := resp_buf.io.deq.valid
  resp_buf.io.deq.ready := 
    (resp_data.io.in.ready || resp_data_in_ready) && (resp_tag.io.in.ready || resp_tag_in_ready)

  when(resp_buf.io.deq.ready) {
    resp_data_in_ready := Bool(false)
    resp_tag_in_ready := Bool(false)
  }.elsewhen(resp_data.io.in.ready) {
    resp_data_in_ready := Bool(true)
  }.elsewhen(resp_tag.io.in.ready) {
    resp_tag_in_ready := Bool(true)
  }

  // converters to output
  resp_data.io.out <> io.outs(0)
  resp_tag.io.out  <> io.outs(1)
}

class MemArbiter(n: Int) extends Module {
  val io = new Bundle {
    val ins = Vec.fill(n){(new MemIO).flip}
    val out = new MemIO  
  }
  val s_READY :: s_READ :: Nil = Enum(UInt(), 2)
  val state = RegInit(s_READY)
  val chosen = RegInit(UInt(n-1))

  io.out.req_cmd.bits := io.ins(chosen).req_cmd.bits
  io.out.req_cmd.valid := io.ins(chosen).req_cmd.valid && state === s_READY
  io.ins.zipWithIndex foreach { case (in, i) => 
    in.req_cmd.ready := io.out.req_cmd.ready && chosen === UInt(i)
  }

  io.out.req_data.bits := io.ins(chosen).req_data.bits
  io.out.req_data.valid := io.ins(chosen).req_data.valid && state === s_READY
  io.ins.zipWithIndex foreach { case (in, i) => 
    in.req_data.ready := io.out.req_data.ready && chosen === UInt(i) 
  }

  io.ins.zipWithIndex foreach { case (in, i) => 
    in.resp.bits := io.out.resp.bits 
    in.resp.valid := io.out.resp.valid && chosen === UInt(i) 
  }
  io.out.resp.ready := io.ins(chosen).resp.ready 

  switch(state) {
    is(s_READY) {
      state := Mux(io.out.req_cmd.valid && !io.out.req_cmd.bits.rw, s_READ, s_READY)
      when(!io.ins(chosen).req_cmd.valid && !io.ins(chosen).req_data.valid) {
        chosen := Mux(chosen.orR, chosen - UInt(1), UInt(n-1))
      }
    }
    is(s_READ) {
      state := Mux(io.ins(chosen).resp.valid, s_READY, s_READ)
    } 
  }
}
