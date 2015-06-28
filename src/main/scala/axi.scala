package strober

import Chisel._
import scala.collection.mutable.ArrayBuffer
import scala.collection.immutable.ListMap

object SimAXI4Wrapper {
  def apply[T <: Module](c: =>T, targetParams: Parameters = Parameters.empty) = {
    val params = (targetParams alter AXI4Params.mask) // alter SimParams.mask
    Module(new SimAXI4Wrapper(new SimWrapper(c)))(params)
  }
}

trait HasMAXI4Addr extends Bundle {
  val axiAddrWidth = params(MAXIAddrWidth)
  val addr = Bits(width=axiAddrWidth)
  val len  = Bits(width=8)
  val size = Bits(width=3)
}

trait HasSAXI4Addr extends Bundle {
  val axiAddrWidth = params(SAXIAddrWidth)
  val addr = Bits(width=axiAddrWidth)
  val len  = Bits(width=8)
  val size = Bits(width=3)
}

trait HasMAXI4Data extends Bundle {
  val axiDataWidth = params(MAXIDataWidth)
  val data = Bits(width=axiDataWidth)
  val last = Bool()
}

trait HasSAXI4Data extends Bundle {
  val axiDataWidth = params(SAXIDataWidth)
  val data = Bits(width=axiDataWidth)
  val last = Bool()
}

trait HasMAXI4Tag extends Bundle {
  val axiTagWidth  = params(MAXITagWidth)
  val id = Bits(width=axiTagWidth) 
}

trait HasSAXI4Tag extends Bundle {
  val axiTagWidth  = params(SAXITagWidth)
  val id = Bits(width=axiTagWidth) 
}

trait HasAXI4Resp extends Bundle {
  val resp = Bits(width=2)
}

class MAXI4Addr extends HasMAXI4Addr with HasMAXI4Tag
class SAXI4Addr extends HasSAXI4Addr with HasSAXI4Tag

class MAXI4Read extends HasMAXI4Data with HasAXI4Resp with HasMAXI4Tag
class SAXI4Read extends HasSAXI4Data with HasAXI4Resp with HasSAXI4Tag

class MAXI4Write extends HasMAXI4Data
class SAXI4Write extends HasSAXI4Data

class MAXI4Resp extends HasAXI4Resp with HasMAXI4Tag
class SAXI4Resp extends HasAXI4Resp with HasSAXI4Tag

class MAXI4_IO extends Bundle { 
  val aw = Decoupled(new MAXI4Addr).flip
  val w  = Decoupled(new MAXI4Write).flip
  val b  = Decoupled(new MAXI4Resp)
  val ar = Decoupled(new MAXI4Addr).flip
  val r  = Decoupled(new MAXI4Read)
}

class SAXI4_IO extends Bundle { 
  val aw = Decoupled(new SAXI4Addr)
  val w  = Decoupled(new SAXI4Write)
  val b  = Decoupled(new SAXI4Resp).flip
  val ar = Decoupled(new SAXI4Addr)
  val r  = Decoupled(new SAXI4Read).flip
}

class AXI4 extends Bundle {
  val M_AXI = new MAXI4_IO
  val S_AXI = new SAXI4_IO
}

class MAXI2Input[T <: Data](gen: T, addr: Int) extends Module {
  val axiAddrWidth = params(MAXIAddrWidth)
  val axiDataWidth = params(MAXIDataWidth)
  val dataWidth = (gen.flatten.unzip._2 foldLeft 0)(_ + _.needWidth)
  val io = new Bundle {
    val addr = UInt(INPUT, axiAddrWidth)
    val in = Decoupled(UInt(width=axiDataWidth)).flip
    val out = Decoupled(gen)
  }
  val s_DATA :: s_DONE :: Nil = Enum(UInt(), 2)
  val state = RegInit(s_DATA)
  val count = RegInit(UInt((dataWidth-1)/axiDataWidth))
  val buf = Reg(gen)
 
  io.in.ready := state === s_DATA
  io.out.bits := buf
  io.out.valid := state === s_DONE

  switch(state) {
    is(s_DATA) {
      when(io.in.fire() && io.addr === UInt(addr)) {
        val b = buf match {
          case p: Packet[_] => p.data
          case b: Bits => b
        }
        b := (b << UInt(axiDataWidth)) | io.in.bits
        if (dataWidth > axiDataWidth) {
          when(count.orR) {
            count := count - UInt(1)
          }.otherwise {
            count := UInt((dataWidth-1)/axiDataWidth)
            state := s_DONE
          }
        } else { 
          state := s_DONE
        }
      }
    }
    is(s_DONE) {
      state := Mux(io.out.ready, s_DATA, s_DONE)
    }
  }
}
 
class Output2MAXI[T <: Data](gen: T, addr: Int) extends Module {
  val axiAddrWidth = params(MAXIAddrWidth)
  val axiDataWidth = params(MAXIDataWidth)
  val dataWidth = (gen.flatten.unzip._2 foldLeft 0)(_ + _.needWidth)
  val io = new Bundle {
    val addr = UInt(INPUT, axiAddrWidth)
    val in = Decoupled(gen).flip
    val out = Decoupled(UInt(width=axiDataWidth))
  }
  val s_IDLE :: s_DATA :: Nil = Enum(UInt(), 2)
  val state = RegInit(s_IDLE)
  val count = RegInit(UInt((dataWidth-1)/axiDataWidth))
  val buf = Reg(gen)

  io.in.ready := state === s_IDLE
  io.out.bits := buf.toBits
  io.out.valid := state === s_DATA

  switch(state) {
    is(s_IDLE) {
      state := Mux(io.in.fire(), s_DATA, s_IDLE)
      buf := io.in.bits
    }
    is(s_DATA) {
      when(io.out.ready && io.addr === UInt(addr)) {
        if (dataWidth > axiDataWidth) {
          val b = buf match {
            case p: Packet[_] => p.data
            case b: Bits => b
          }
          b := b.toUInt >> UInt(axiDataWidth)
          when(count.orR) {
            count := count - UInt(1)
          }.otherwise {
            count := UInt((dataWidth-1)/axiDataWidth)
            state := s_IDLE
          }
        } else {
          state := s_IDLE
        }
      }
    }
  }
}

class SimAXI4Wrapper[+T <: SimNetwork](c: =>T) extends Module {
  // params
  val m_axiAddrWidth = params(MAXIAddrWidth)
  val m_axiDataWidth = params(MAXIDataWidth)
  val addrSize = params(MAXIAddrSize)
  val addrOffset = params(MAXIAddrOffset)
  val resetAddr = params(ResetAddr)
  val s_axiAddrWidth = params(SAXIAddrWidth)
  val s_axiDataWidth = params(SAXIDataWidth)
  val memAddrWidth = params(MemAddrWidth)
  val memDataWidth = params(MemDataWidth)
  val memDataCount = params(MemDataCount)
  val memTagWidth = params(MemTagWidth)
  val blockOffset = params(BlockOffset)
  private val s_axiAddrOffset = scala.math.max(blockOffset, log2Up(s_axiDataWidth>>3))
  private val memAddrOffset = memAddrWidth + s_axiAddrOffset
  private val dataCountLimit = ((1 << (blockOffset+3)) - 1) / s_axiDataWidth
  private val dataChunkLimit = (memDataWidth - 1) / s_axiDataWidth
  require(dataCountLimit >= dataChunkLimit && (dataChunkLimit == 0 || dataCountLimit % dataChunkLimit == 0))

  val io = new AXI4
  // Simulation Target
  val sim: T = Module(c)
  val (memInMap, ioInMap) = 
    ListMap((sim.io.t_ins.unzip._2 zip sim.io.ins):_*) partition (MemIO.ins contains _._1)
  val (memOutMap, ioOutMap) = 
    ListMap((sim.io.t_outs.unzip._2 zip sim.io.outs):_*) partition (MemIO.outs contains _._1)

  // MemIO Converter
  val mem_conv = Module(new MAXI_MemIOConverter(ioInMap.size, ioOutMap.size))
  // Fake wires for accesses from outside FPGA
  val mem_req_cmd_addr = UInt(INPUT, memAddrWidth)
  val mem_req_cmd_tag = UInt(INPUT, memTagWidth+1)
  val mem_req_data = UInt(INPUT, memDataWidth)
  val mem_resp_data = UInt(OUTPUT, memDataWidth)
  val mem_resp_tag = UInt(OUTPUT, memTagWidth)

  /*** M_AXI INPUTS ***/
  val waddr_r = RegInit(UInt(0, addrSize))
  val awid_r  = RegInit(UInt(0))
  val st_wr_idle::st_wr_write::st_wr_ack :: Nil = Enum(UInt(), 3)
  val st_wr = RegInit(st_wr_idle)
  val do_write = st_wr === st_wr_write
  val reset_t = do_write && (waddr_r === UInt(resetAddr))
  sim.reset := reset_t
  mem_conv.reset := reset_t

  val in_convs = ioInMap.zipWithIndex map { case ((wire, in), id) =>
    val converter = Module(new MAXI2Input(in.bits, id))
    converter.name = "in_conv_" + id
    converter.reset := reset_t
    converter.io.addr := waddr_r
    converter.io.in.bits := io.M_AXI.w.bits.data
    converter.io.in.valid := do_write
    converter.io.out <> in
    converter
  }
  val in_ready = Vec(in_convs map (_.io.in.ready))
  mem_conv.io.in_addr := waddr_r
  mem_conv.io.in.bits := io.M_AXI.w.bits.data
  mem_conv.io.in.valid := do_write

  // M_AXI Write FSM
  switch(st_wr) {
    is(st_wr_idle) {
      when(io.M_AXI.aw.valid && io.M_AXI.w.valid) {
        st_wr   := st_wr_write
        waddr_r := io.M_AXI.aw.bits.addr >> UInt(addrOffset)
        awid_r  := io.M_AXI.aw.bits.id
      }
    }
    is(st_wr_write) {
      when(Mux(waddr_r < UInt(ioInMap.size), in_ready(waddr_r), mem_conv.io.in.ready) || 
               waddr_r === UInt(resetAddr)) {
        st_wr := st_wr_ack
      } 
    }
    is(st_wr_ack) {
      when(io.M_AXI.b.ready) {
        st_wr := st_wr_idle
      }
    }
  }
  io.M_AXI.aw.ready    := do_write
  io.M_AXI.w.ready     := do_write
  io.M_AXI.b.valid     := st_wr === st_wr_ack
  io.M_AXI.b.bits.id   := awid_r
  io.M_AXI.b.bits.resp := Bits(0)

  /*** M_AXI OUTPUS ***/
  val raddr_r = RegInit(UInt(0, addrSize))
  val arid_r  = RegInit(UInt(0))
  val st_rd_idle :: st_rd_read :: Nil = Enum(UInt(), 2)
  val st_rd = RegInit(st_rd_idle)
  val do_read = st_rd === st_rd_read  

  val out_convs = ioOutMap.zipWithIndex map { case ((wire, out), id) =>
    val converter = Module(new Output2MAXI(out.bits, id))
    converter.name = "out_conv_" + id
    converter.reset := reset_t
    converter.io.addr := raddr_r
    out <> converter.io.in
    converter.io.out.ready := do_read && io.M_AXI.r.ready 
    converter
  }
  val out_data = Vec(out_convs map (_.io.out.bits))
  val out_valid = Vec(out_convs map (_.io.out.valid))
  mem_conv.io.out_addr := raddr_r
  mem_conv.io.out.ready := do_read && io.M_AXI.r.ready

  // M_AXI Read FSM
  switch(st_rd) {
    is(st_rd_idle) {
      when(io.M_AXI.ar.valid) {
        st_rd   := st_rd_read
        raddr_r := io.M_AXI.ar.bits.addr >> UInt(addrOffset)
        arid_r  := io.M_AXI.ar.bits.id
      }
    }
    is(st_rd_read) {
      when(io.M_AXI.r.ready) {
        st_rd   := st_rd_idle
      }
    }
  }
  io.M_AXI.ar.ready    := st_rd === st_rd_idle
  io.M_AXI.r.valid     := Mux(raddr_r < UInt(ioOutMap.size), out_valid(raddr_r), mem_conv.io.out.valid) && do_read
  io.M_AXI.r.bits.data := Mux(raddr_r < UInt(ioOutMap.size), out_data(raddr_r), mem_conv.io.out.bits) 
  io.M_AXI.r.bits.last := io.M_AXI.r.valid
  io.M_AXI.r.bits.id   := arid_r

  /*** S_AXI ***/
  val mem = Module(new MemArbiter(MemIO.count+1))
  mem.reset := reset_t
  // Connect Memory Signal Channals to MemUnit
  for (i <- 0 until MemIO.count) {
    val conv = Module(new ChannelMemIOConverter)
    conv.name = "mem_conv_" + i 
    conv.reset := reset_t
    conv.io.req_cmd_ready <> memInMap(MemReqCmd(i)(0))
    conv.io.req_cmd_valid <> memOutMap(MemReqCmd(i)(1))
    conv.io.req_cmd_addr <> memOutMap(MemReqCmd(i)(2))
    conv.io.req_cmd_tag <> memOutMap(MemReqCmd(i)(3))
    conv.io.req_cmd_rw <> memOutMap(MemReqCmd(i)(4))

    conv.io.req_data_ready <> memInMap(MemData(i)(0))
    conv.io.req_data_valid <> memOutMap(MemData(i)(1))
    conv.io.req_data_bits <> memOutMap(MemData(i)(2))

    conv.io.resp_ready <> memOutMap(MemResp(i)(0))
    conv.io.resp_valid <> memInMap(MemResp(i)(1))
    conv.io.resp_data <> memInMap(MemResp(i)(2))
    conv.io.resp_tag <> memInMap(MemResp(i)(3))

    conv.io.mem <> mem.io.ins(i)
  }
  mem_conv.io.mem <> mem.io.ins(MemIO.count)

  val st_idle :: st_read :: st_start_write :: st_write :: Nil = Enum(UInt(), 4)
  val state_r = RegInit(st_idle)
  val do_mem_write = state_r === st_write
  val resp_data_buf = Vec.fill(dataChunkLimit){Reg(UInt(width=s_axiDataWidth))}
  val read_count = RegInit(UInt(0, log2Up(dataChunkLimit+1)))
  val write_count = RegInit(UInt(0, log2Up(dataCountLimit+1)))
  val s_axi_addr = 
    if (memAddrOffset == s_axiAddrWidth - 4)
      Cat(UInt(1, 4), mem.io.out.req_cmd.bits.addr, UInt(0, s_axiAddrOffset))
    else if (memAddrOffset > s_axiAddrWidth - 4) 
      Cat(UInt(1, 4), mem.io.out.req_cmd.bits.addr(s_axiAddrWidth-4-s_axiAddrOffset-1,0), UInt(0, s_axiAddrOffset))
    else
      Cat(UInt(1, 4), UInt(0, s_axiAddrWidth-4-memAddrOffset), mem.io.out.req_cmd.bits.addr, UInt(0, s_axiAddrOffset))

  mem.io.out.req_cmd.ready := 
    (state_r === st_start_write && io.S_AXI.aw.ready) || (state_r === st_read && io.S_AXI.ar.ready)

  /* S_AXI Read Signals */
  // Read Address
  io.S_AXI.ar.bits.addr := s_axi_addr
  io.S_AXI.ar.bits.id := mem.io.out.req_cmd.bits.tag
  io.S_AXI.ar.bits.len := UInt(dataCountLimit) // burst length (transfers)
  io.S_AXI.ar.bits.size := UInt(log2Up(scala.math.min(memDataWidth, s_axiDataWidth))-3) // burst size (bits/beat)
  io.S_AXI.ar.valid := state_r === st_read
  // Read Data
  io.S_AXI.r.ready := mem.io.out.resp.ready
  mem.io.out.resp.valid := io.S_AXI.r.valid && 
    (if (dataChunkLimit > 0) read_count === UInt(dataChunkLimit) else Bool(true))
  mem.io.out.resp.bits.data := 
    (if (dataChunkLimit > 0) Cat(io.S_AXI.r.bits.data :: resp_data_buf.toList.reverse) else io.S_AXI.r.bits.data)
  mem.io.out.resp.bits.tag := io.S_AXI.r.bits.id

  /* S_AXI Write Signals */
  // Write Address
  io.S_AXI.aw.bits.addr := s_axi_addr
  io.S_AXI.aw.bits.id := UInt(0)
  io.S_AXI.aw.bits.len := io.S_AXI.ar.bits.len // burst length (transfers)
  io.S_AXI.aw.bits.size := io.S_AXI.ar.bits.size // burst size (bits/beat)
  io.S_AXI.aw.valid := state_r === st_start_write
  // Write Data
  io.S_AXI.w.valid := do_mem_write && mem.io.out.req_data.valid
  io.S_AXI.w.bits.data := {
    val wire = mem.io.out.req_data.bits.data 
    val width = s_axiDataWidth
    if (dataChunkLimit > 0) Lookup(write_count(log2Up(dataChunkLimit+1)-1, 0), wire(width-1,0),
      ((1 to dataChunkLimit) map (i => ((UInt(i) -> wire((i+1)*width-1,i*width))))).toList)
    else wire
  }
  io.S_AXI.w.bits.last := io.S_AXI.w.valid &&
    (if (dataCountLimit > 0) write_count === UInt(dataCountLimit) else Bool(true))
  mem.io.out.req_data.ready := io.S_AXI.w.fire() && 
    (if (dataChunkLimit > 0) write_count(log2Up(dataChunkLimit+1)-1, 0) === UInt(dataChunkLimit) else Bool(true))
  // Write Response
  io.S_AXI.b.ready := Bool(true)


  if (dataChunkLimit > 0) {
    when(io.S_AXI.r.fire()) {
      read_count := Mux(read_count === UInt(dataChunkLimit), UInt(0), read_count + UInt(1))
    }
    when(io.S_AXI.r.valid) {
      resp_data_buf(read_count) := io.S_AXI.r.bits.data
    }
  }

  /* FSM for memory requests */
  switch(state_r) {
    is(st_idle) {
      state_r := 
        Mux(mem.io.out.req_cmd.valid && !mem.io.out.req_cmd.bits.rw, st_read,
        Mux(mem.io.out.req_cmd.valid && mem.io.out.req_cmd.bits.rw && mem.io.out.req_data.valid, 
            st_start_write, st_idle))
    }
    is(st_read) {
      state_r := Mux(io.S_AXI.ar.ready, st_idle, st_read)
    }
    is(st_start_write) {
      state_r := Mux(io.S_AXI.aw.ready, st_write, st_start_write)
    }
    is(st_write) {
      when(io.S_AXI.w.ready && mem.io.out.req_data.valid) {
        if (dataChunkLimit > 0) {
          write_count := write_count + UInt(1)
          when (io.S_AXI.w.bits.last) {
            write_count := UInt(0)
            state_r := st_idle
          }
        } else {
          state_r := st_idle 
        }
      }
    }
  }

  transforms.init(this) 
}
