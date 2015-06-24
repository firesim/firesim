package strober

import Chisel._
import scala.collection.mutable.{ArrayBuffer}

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

  def apply(i: Int) = (ready_wires(i), valid_wires(i), addr_wires(i), tag_wires(i), rw_wires(i))
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

object MemReqData {
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

  def apply(i: Int) = (ready_wires(i), valid_wires(i), bits_wires(i))
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

  def apply(i: Int) = (ready_wires(i), valid_wires(i), data_wires(i), tag_wires(i))
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
  def ins = MemReqCmd.ins ++ MemReqData.ins ++ MemResp.ins    
  def outs = MemReqCmd.outs ++ MemReqData.outs ++ MemResp.outs
  def count = {
    assert(MemReqCmd.size == MemReqData.size)
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

class MemUnitIO(n: Int) extends Bundle {
  val addrWidth = params(MemAddrWidth)
  val memWidth = params(MemDataWidth) 
  val tagWidth = params(MemTagWidth)

  val req_cmd_ready = Vec.fill(n){Decoupled(new Packet(Bool()))}
  val req_cmd_valid = Vec.fill(n){Decoupled(new Packet(Bool())).flip}
  val req_cmd_addr = Vec.fill(n){Decoupled(new Packet(UInt(width=addrWidth))).flip}
  val req_cmd_tag = Vec.fill(n){Decoupled(new Packet(UInt(width=tagWidth))).flip}
  val req_cmd_rw = Vec.fill(n){Decoupled(new Packet(Bool())).flip}

  val req_data_ready = Vec.fill(n){Decoupled(new Packet(Bool()))}
  val req_data_valid = Vec.fill(n){Decoupled(new Packet(Bool())).flip}
  val req_data_bits = Vec.fill(n){Decoupled(new Packet(UInt(width=memWidth))).flip}

  val resp_ready = Vec.fill(n){Decoupled(new Packet(Bool())).flip}
  val resp_valid = Vec.fill(n){Decoupled(new Packet(Bool()))}
  val resp_data = Vec.fill(n){Decoupled(new Packet(UInt(width=memWidth)))}
  val resp_tag = Vec.fill(n){Decoupled(new Packet(UInt(width=tagWidth)))}

  val out = new MemIO
}

class MemUnit(n: Int = 1) extends Module {
  val io = new MemUnitIO(n)
  val req_cmd_bufs = (0 until n) map { i => Module(new Queue(new MemReqCmd, 2)) }
  val req_data_bufs = (0 until n) map { i => Module(new Queue(new MemData, 2)) }
  val resp_bufs = (0 until n) map { i => Module(new Queue(new MemResp, 2)) }
  val inflight = Vec.fill(n) { RegInit(Bool(false)) }

  // Todo: fix this in Chisel(naming problem)
  req_cmd_bufs.zipWithIndex map { case (x, i) => x.name = "req_cmd_buf_" + i }
  req_data_bufs.zipWithIndex map { case (x, i) => x.name = "req_data_buf_" + i }
  resp_bufs.zipWithIndex map { case (x, i) => x.name = "resp_buf_" + i }

  for (i <- 0 until n) {
    val req_cmd_buf = req_cmd_bufs(i)
    val req_data_buf = req_data_bufs(i)
    val resp_buf = resp_bufs(i)
    val req_cmd_valid = io.req_cmd_ready(i).ready && io.req_cmd_valid(i).valid &&
      io.req_cmd_addr(i).valid && io.req_cmd_tag(i).valid && io.req_cmd_rw(i).valid
    val req_data_valid = io.req_data_ready(i).ready && io.req_data_valid(i).valid &&
      io.req_data_bits(i).valid
    val req_fire = !inflight(i) && req_cmd_valid && req_data_valid && 
      req_cmd_buf.io.enq.ready && req_data_buf.io.enq.ready
    val resp_fire = io.resp_ready(i).valid && io.resp_valid(i).ready &&
      io.resp_data(i).ready && io.resp_tag(i).ready

    // TODO: fix this in Chisel(naming problem)
    req_cmd_valid.getNode nameIt ("req_cmd_ready_" + i, false)
    req_data_valid.getNode nameIt ("req_data_ready_" + i, false)
    req_fire.getNode nameIt ("req_fire_" + i, false)
    resp_fire.getNode nameIt ("resp_fire" + i, false)

    req_cmd_buf.io.enq.bits.addr := io.req_cmd_addr(i).bits.data
    req_cmd_buf.io.enq.bits.tag := io.req_cmd_tag(i).bits.data
    req_cmd_buf.io.enq.bits.rw := io.req_cmd_rw(i).bits.data
    // Send memory request only when valid
    req_cmd_buf.io.enq.valid := req_fire && io.req_cmd_valid(i).bits.data
    // Consums timing tokens 
    io.req_cmd_valid(i).ready := req_fire
    io.req_cmd_addr(i).ready := req_fire
    io.req_cmd_tag(i).ready := req_fire
    io.req_cmd_rw(i).ready := req_fire
    // 
    io.req_cmd_ready(i).bits.data := io.req_cmd_valid(i).bits.data 
    io.req_cmd_ready(i).valid := (req_fire && !io.req_cmd_valid(i).bits.data) || req_fire

    req_data_buf.io.enq.bits.data := io.req_data_bits(i).bits.data
    // Send memory data only when valid
    req_data_buf.io.enq.valid := req_fire && io.req_data_valid(i).bits.data
    // Consums timing tokens 
    io.req_data_valid(i).ready := req_fire
    io.req_data_bits(i).ready := req_fire
    // 
    io.req_data_ready(i).bits.data := io.req_data_valid(i).bits.data 
    io.req_data_ready(i).valid := (req_fire && !io.req_cmd_valid(i).bits.data) || req_fire

    resp_buf.io.deq.ready := resp_fire
    io.resp_ready(i).ready := resp_fire &&
      ((req_fire && (!io.req_cmd_valid(i).bits.data || io.req_cmd_rw(i).bits.data)) || resp_buf.io.deq.valid)
    io.resp_valid(i).bits.data := resp_buf.io.deq.valid
    io.resp_valid(i).valid := io.resp_ready(i).ready
    io.resp_data(i).bits.data := resp_buf.io.deq.bits.data
    io.resp_data(i).valid := io.resp_ready(i).ready
    io.resp_tag(i).bits.data := resp_buf.io.deq.bits.tag
    io.resp_tag(i).valid := io.resp_ready(i).ready

    when (req_fire && io.req_cmd_rw(i).bits.data) {
      inflight(i) := Bool(true)
    }.elsewhen(resp_buf.io.deq.valid) {
      inflight(i) := Bool(false)
    }
  }

  if (n > 1) {
    // TODO: multi MemIOs
  } else {
    val req_cmd_buf = req_cmd_bufs.head
    val req_data_buf = req_data_bufs.head
    val resp_buf = resp_bufs.head
    io.out.req_cmd <> req_cmd_buf.io.deq
    io.out.req_data <> req_data_buf.io.deq
    resp_buf.io.enq <> io.out.resp
  }
}
