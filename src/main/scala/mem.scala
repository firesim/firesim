package strober

import Chisel._
import scala.collection.mutable.ArrayBuffer

object MemReqCmd {
  private val ready_wires = ArrayBuffer[Bool]()
  private val valid_wires = ArrayBuffer[Bool]()
  private val addr_wires = ArrayBuffer[Bits]()
  private val tag_wires = ArrayBuffer[Bits]()
  private val rw_wires = ArrayBuffer[Bits]()

  def ready(wire: Bool) {
    assert(wire.dir == INPUT)
    ready_wires += wire
  }
  def valid(wire: Bool) {
    assert(wire.dir == OUTPUT)
    valid_wires += wire
  }
  def addr(wire: Bits) {
    assert(wire.dir == OUTPUT)
    addr_wires += wire
  }
  def tag(wire: Bits) {
    assert(wire.dir == OUTPUT)
    tag_wires += wire
  }
  def rw(wire: Bool) {
    assert(wire.dir == OUTPUT)
    rw_wires += wire
  }

  def apply(i: Int) = Vector(ready_wires(i), valid_wires(i), addr_wires(i), tag_wires(i), rw_wires(i))
  def ins = ready_wires.toSet  
  def outs = (valid_wires ++ addr_wires ++ tag_wires ++ rw_wires).toSet
  def size = {
    assert(ready_wires.size == valid_wires.size)
    assert(ready_wires.size == addr_wires.size)
    assert(ready_wires.size == tag_wires.size)
    assert(ready_wires.size == rw_wires.size)
    ready_wires.size
  }
}

object MemData {
  private val ready_wires = ArrayBuffer[Bool]()
  private val valid_wires = ArrayBuffer[Bool]()
  private val bits_wires = ArrayBuffer[Bits]()

  def ready(wire: Bool) {
    assert(wire.dir == INPUT)
    ready_wires += wire
  }
  def valid(wire: Bool) {
    assert(wire.dir == OUTPUT)
    valid_wires += wire
  }
  def bits(wire: Bits) {
    assert(wire.dir == OUTPUT)
    bits_wires += wire
  }

  def apply(i: Int) = Vector(ready_wires(i), valid_wires(i), bits_wires(i))
  def ins = ready_wires.toSet  
  def outs = (valid_wires ++ bits_wires).toSet
  def size = {
    assert(ready_wires.size == valid_wires.size)
    assert(ready_wires.size == bits_wires.size)
    ready_wires.size
  }
}

object MemResp {
  private val ready_wires = ArrayBuffer[Bool]()
  private val valid_wires = ArrayBuffer[Bool]()
  private val data_wires = ArrayBuffer[Bits]()
  private val tag_wires = ArrayBuffer[Bits]()

  def ready(wire: Bool) {
    assert(wire.dir == OUTPUT)
    ready_wires += wire
  }
  def valid(wire: Bool) {
    assert(wire.dir == INPUT)
    valid_wires += wire
  }
  def data(wire: Bits) {
    assert(wire.dir == INPUT)
    data_wires += wire
  }
  def tag(wire: Bits) {
    assert(wire.dir == INPUT)
    tag_wires += wire
  }

  def apply(i: Int) = Vector(ready_wires(i), valid_wires(i), data_wires(i), tag_wires(i))
  def ins = (valid_wires ++ data_wires ++ tag_wires).toSet
  def outs = ready_wires.toSet  
  def size = {
    assert(ready_wires.size == valid_wires.size)
    assert(ready_wires.size == data_wires.size)
    assert(ready_wires.size == tag_wires.size)
    ready_wires.size
  }
}

object MemIO {
  def ins = MemReqCmd.ins ++ MemData.ins ++ MemResp.ins    
  def outs = MemReqCmd.outs ++ MemData.outs ++ MemResp.outs
  def wires = ins ++ outs
  def count = {
    assert(MemReqCmd.size == MemData.size)
    assert(MemReqCmd.size == MemResp.size)
    MemReqCmd.size
  }
}

trait HasMemData extends Bundle {
  val memWidth = params(MemDataWidth) 
  val data = UInt(width=memWidth)
}

trait HasMemAddr extends Bundle {
  val addrWidth = params(MemAddrWidth)
  val addr = UInt(width=addrWidth)
}

trait HasMemTag extends Bundle {
  val tagWidth = params(MemTagWidth)
  val tag = UInt(width=tagWidth)
}

class MemReqCmd extends HasMemAddr with HasMemTag {
  val rw = Bool()
}
class MemResp extends HasMemData with HasMemTag
class MemData extends HasMemData

class MemIO extends Bundle {
  val req_cmd  = Decoupled(new MemReqCmd)
  val req_data = Decoupled(new MemData)
  val resp     = Decoupled(new MemResp).flip
}

class Channels2MemIO extends Module {
  val addrWidth = params(MemAddrWidth)
  val memWidth = params(MemDataWidth) 
  val tagWidth = params(MemTagWidth)

  val io = new Bundle {
    val req_cmd_ready = Decoupled(new Packet(Bool()))
    val req_cmd_valid = Decoupled(new Packet(Bool())).flip
    val req_cmd_addr = Decoupled(new Packet(UInt(width=addrWidth))).flip
    val req_cmd_tag = Decoupled(new Packet(UInt(width=tagWidth))).flip
    val req_cmd_rw = Decoupled(new Packet(Bool())).flip

    val req_data_ready = Decoupled(new Packet(Bool()))
    val req_data_valid = Decoupled(new Packet(Bool())).flip
    val req_data_bits = Decoupled(new Packet(UInt(width=memWidth))).flip

    val resp_ready = Decoupled(new Packet(Bool())).flip
    val resp_valid = Decoupled(new Packet(Bool()))
    val resp_data = Decoupled(new Packet(UInt(width=memWidth)))
    val resp_tag = Decoupled(new Packet(UInt(width=tagWidth)))
    
    val mem = new MemIO
  }

  val req_cmd_buf = Module(new Queue(new MemReqCmd, 2))
  val req_data_buf = Module(new Queue(new MemData, 2)) 
  val resp_buf = Module(new Queue(new MemResp, 2))
  val req_cmd_valid = io.req_cmd_ready.ready && io.req_cmd_valid.valid &&
    io.req_cmd_addr.valid && io.req_cmd_tag.valid && io.req_cmd_rw.valid
  val req_data_valid = io.req_data_ready.ready && io.req_data_valid.valid && io.req_data_bits.valid
  val req_fire = req_cmd_valid && req_data_valid && req_cmd_buf.io.enq.ready && req_data_buf.io.enq.ready
  val resp_fire = io.resp_ready.valid && io.resp_valid.ready && io.resp_data.ready && io.resp_tag.ready
  req_cmd_buf.io.enq.bits.addr := io.req_cmd_addr.bits.data
  req_cmd_buf.io.enq.bits.tag := io.req_cmd_tag.bits.data
  req_cmd_buf.io.enq.bits.rw := io.req_cmd_rw.bits.data
  // Send memory request only when valid
  req_cmd_buf.io.enq.valid := req_fire && io.req_cmd_valid.bits.data
  // Consums timing tokens 
  io.req_cmd_valid.ready := req_fire
  io.req_cmd_addr.ready := req_fire
  io.req_cmd_tag.ready := req_fire
  io.req_cmd_rw.ready := req_fire
  // 
  io.req_cmd_ready.bits.data := io.req_cmd_valid.bits.data 
  io.req_cmd_ready.valid := (req_fire && !io.req_cmd_valid.bits.data) || req_fire

  req_data_buf.io.enq.bits.data := io.req_data_bits.bits.data
  // Send memory data only when valid
  req_data_buf.io.enq.valid := req_fire && io.req_data_valid.bits.data
  // Consums timing tokens 
  io.req_data_valid.ready := req_fire
  io.req_data_bits.ready := req_fire
  // 
  io.req_data_ready.bits.data := io.req_data_valid.bits.data 
  io.req_data_ready.valid := (req_fire && !io.req_cmd_valid.bits.data) || req_fire

  resp_buf.io.deq.ready := resp_fire
  io.resp_ready.ready := resp_fire &&
    ((req_fire && (!io.req_cmd_valid.bits.data || io.req_cmd_rw.bits.data)) || resp_buf.io.deq.valid)
  io.resp_valid.bits.data := resp_buf.io.deq.valid
  io.resp_valid.valid := io.resp_ready.ready
  io.resp_data.bits.data := resp_buf.io.deq.bits.data
  io.resp_data.valid := io.resp_ready.ready
  io.resp_tag.bits.data := resp_buf.io.deq.bits.tag
  io.resp_tag.valid := io.resp_ready.ready

  io.mem.req_cmd <> req_cmd_buf.io.deq
  io.mem.req_data <> req_data_buf.io.deq
  resp_buf.io.enq <> io.mem.resp
}

class MAXI_MemIOConverter(rAddrOffset: Int, wAddrOffset: Int) extends Module {
  val axiAddrWidth = params(MAXIAddrWidth)
  val axiDataWidth = params(MAXIDataWidth)
  val addrWidth = params(MemAddrWidth)
  val memWidth = params(MemDataWidth) 
  val tagWidth = params(MemTagWidth)
  val io = new Bundle {
    val in = Decoupled(UInt(width=axiDataWidth)).flip
    val in_addr = UInt(INPUT, axiAddrWidth)
    val out = Decoupled(UInt(width=axiDataWidth))
    val out_addr = UInt(INPUT, axiAddrWidth)
    val mem = new MemIO
  }
  // rAddrOffset + 0 : req_cmd_addr
  // rAddrOffset + 1 : {req_cmd_tag, req_cmd_rw}
  // rAddrOffset + 2 : req_cmd_data
  // wAddrOffset + 0 : resp_data
  // wAddrOffset + 1 : resp_tag
  val req_cmd_addr = Module(new MAXI2Input(UInt(width=addrWidth), rAddrOffset))
  val req_cmd_tag = Module(new MAXI2Input(UInt(width=tagWidth+1), rAddrOffset+1))
  val req_data = Module(new MAXI2Input(UInt(width=memWidth), rAddrOffset+2))
  val req_cmd_buf = Module(new Queue(new MemReqCmd, 2))
  val req_data_buf = Module(new Queue(new MemData, 2)) 
  val resp_buf = Module(new Queue(new MemResp, 2))
  val resp_data = Module(new Output2MAXI(UInt(width=memWidth), wAddrOffset))
  val resp_tag = Module(new Output2MAXI(UInt(width=tagWidth), wAddrOffset+1))

  // input to converters
  val req_ready = Vec(req_cmd_addr.io.in.ready, req_cmd_tag.io.in.ready, req_data.io.in.ready)
  req_cmd_addr.io.in.bits := io.in.bits
  req_cmd_addr.io.in.valid := io.in.valid
  req_cmd_addr.io.addr := io.in_addr
  req_cmd_tag.io.in.bits := io.in.bits
  req_cmd_tag.io.in.valid := io.in.valid
  req_cmd_tag.io.addr := io.in_addr
  req_data.io.in.bits := io.in.bits
  req_data.io.in.valid := io.in.valid
  req_data.io.addr := io.in_addr
  io.in.ready := req_ready(io.in_addr - UInt(rAddrOffset))
  
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
  resp_data.io.in.bits := resp_buf.io.deq.bits.data
  resp_data.io.in.valid := resp_buf.io.deq.valid
  resp_tag.io.in.bits := resp_buf.io.deq.bits.tag
  resp_tag.io.in.valid := resp_buf.io.deq.valid
  resp_buf.io.deq.ready := resp_data.io.in.ready && resp_tag.io.in.ready
  
  // converters to output
  val resp_bits = Vec(resp_data.io.out.bits, resp_tag.io.out.bits)
  val resp_valid = Vec(resp_data.io.out.valid, resp_tag.io.out.valid)
  io.out.bits := resp_bits(io.out_addr - UInt(wAddrOffset)) 
  io.out.valid := resp_valid(io.out_addr - UInt(wAddrOffset))
  resp_data.io.out.ready := io.out.ready
  resp_tag.io.out.ready := io.out.ready
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
