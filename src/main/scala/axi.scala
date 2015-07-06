package strober

import Chisel._

object SimAXI4Wrapper {
  def apply[T <: Module](c: =>T, targetParams: Parameters = Parameters.empty) = {
    val params = (targetParams alter AXI4Params.mask) alter SimParams.mask
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

  io.in.ready := Bool(false)
  io.out.bits := buf.toBits
  io.out.valid := state === s_DATA

  switch(state) {
    is(s_IDLE) {
      state := Mux(io.in.valid, s_DATA, s_IDLE)
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
            io.in.ready := Bool(true)
          }
        } else {
          state := s_IDLE
          io.in.ready := Bool(true)
        }
      }
      // flush when the input is no longer valid
      when(!io.in.valid) {
        state := s_IDLE
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
  val sramRestartAddr = params(SRAMRestartAddr)
  val s_axiAddrWidth = params(SAXIAddrWidth)
  val s_axiDataWidth = params(SAXIDataWidth)
  val memAddrWidth = params(MemAddrWidth)
  val memDataWidth = params(MemDataWidth)
  val memDataCount = params(MemDataCount)
  val memTagWidth = params(MemTagWidth)
  val memBlockSize = params(MemBlockSize)
  val memBlockOffset = params(MemBlockOffset)
  private val s_axiAddrOffset = scala.math.max(memBlockOffset, log2Up(s_axiDataWidth>>3))
  private val memAddrOffset = memAddrWidth + s_axiAddrOffset
  private val dataCountLimit = (memBlockSize - 1) / (s_axiDataWidth>>3)
  private val dataChunkLimit = (memDataWidth - 1) / s_axiDataWidth
  require(dataCountLimit >= dataChunkLimit && (dataChunkLimit == 0 || (dataCountLimit+1) % (dataChunkLimit+1) == 0),
    "dataCountLimit+1 = %d, dataChunkLimit+1 = %d".format(dataCountLimit+1, dataChunkLimit+1))

  val io = new AXI4
  // Simulation Target
  val sim: T = Module(c)

  // Fake wires for snapshotting
  val snap_out = new Bundle {
    val regs = UInt(OUTPUT, sim.daisyWidth)
    val sram = UInt(OUTPUT, sim.daisyWidth)
    val cntr = UInt(OUTPUT, sim.daisyWidth)
  }
  // Fake wires for accesses from outside FPGA
  val mem_req = new Bundle {
    val addr = UInt(INPUT, memAddrWidth)
    val tag = UInt(INPUT, memTagWidth+1)
    val data = UInt(INPUT, memDataWidth)
  }
  val mem_resp = new Bundle {
    val data = UInt(OUTPUT, memDataWidth)
    val tag = UInt(OUTPUT, memTagWidth)
  }

  /*** M_AXI INPUTS ***/
  val waddr_r = RegInit(UInt(0, addrSize))
  val awid_r  = RegInit(UInt(0))
  val st_wr_idle :: st_wr_write :: st_wr_ack :: Nil = Enum(UInt(), 3)
  val st_wr = RegInit(st_wr_idle)
  val do_write = st_wr === st_wr_write
  val reset_t = do_write && (waddr_r === UInt(resetAddr))
  val sram_restart = do_write && (waddr_r === UInt(sramRestartAddr))
  val in_ready = Bool() // Connected in the backend 

  sim.reset := reset_t
  sim.io.daisy.sram.restart := sram_restart

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
      when(in_ready || waddr_r === UInt(resetAddr) || waddr_r === UInt(sramRestartAddr)) {
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
  val out_data = UInt() // Connected in the backend 
  val out_valid = Bool() // Connected in the backend 

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
  io.M_AXI.r.valid     := out_valid && do_read
  io.M_AXI.r.bits.data := out_data
  io.M_AXI.r.bits.last := io.M_AXI.r.valid
  io.M_AXI.r.bits.id   := arid_r

  /*** S_AXI ***/
  val mem = new MemIO // connected in the backend
  val st_idle :: st_read :: st_start_write :: st_write :: Nil = Enum(UInt(), 4)
  val state_r = RegInit(st_idle)
  val do_mem_write = state_r === st_write
  val resp_data_buf = Vec.fill(dataChunkLimit){Reg(UInt(width=s_axiDataWidth))}
  val read_count = RegInit(UInt(0, log2Up(dataChunkLimit+1)))
  val write_count = RegInit(UInt(0, log2Up(dataCountLimit+1)))
  val s_axi_addr = 
    if (memAddrOffset == s_axiAddrWidth - 4)
      Cat(UInt(1, 4), mem.req_cmd.bits.addr, UInt(0, s_axiAddrOffset))
    else if (memAddrOffset > s_axiAddrWidth - 4) 
      Cat(UInt(1, 4), mem.req_cmd.bits.addr(s_axiAddrWidth-4-s_axiAddrOffset-1,0), UInt(0, s_axiAddrOffset))
    else
      Cat(UInt(1, 4), UInt(0, s_axiAddrWidth-4-memAddrOffset), mem.req_cmd.bits.addr, UInt(0, s_axiAddrOffset))

  mem.req_cmd.ready := 
    (state_r === st_start_write && io.S_AXI.aw.ready) || (state_r === st_read && io.S_AXI.ar.ready)

  /* S_AXI Read Signals */
  // Read Address
  io.S_AXI.ar.bits.addr := s_axi_addr
  io.S_AXI.ar.bits.id := mem.req_cmd.bits.tag
  io.S_AXI.ar.bits.len := UInt(dataCountLimit) // burst length (transfers)
  io.S_AXI.ar.bits.size := UInt(log2Up(scala.math.min(memDataWidth, s_axiDataWidth))-3) // burst size (bits/beat)
  io.S_AXI.ar.valid := state_r === st_read
  // Read Data
  io.S_AXI.r.ready := mem.resp.ready
  mem.resp.valid := io.S_AXI.r.valid && 
    (if (dataChunkLimit > 0) read_count === UInt(dataChunkLimit) else Bool(true))
  mem.resp.bits.data := 
    (if (dataChunkLimit > 0) Cat(io.S_AXI.r.bits.data :: resp_data_buf.toList.reverse) else io.S_AXI.r.bits.data)
  mem.resp.bits.tag := io.S_AXI.r.bits.id

  /* S_AXI Write Signals */
  // Write Address
  io.S_AXI.aw.bits.addr := s_axi_addr
  io.S_AXI.aw.bits.id := UInt(0)
  io.S_AXI.aw.bits.len := io.S_AXI.ar.bits.len // burst length (transfers)
  io.S_AXI.aw.bits.size := io.S_AXI.ar.bits.size // burst size (bits/beat)
  io.S_AXI.aw.valid := state_r === st_start_write
  // Write Data
  io.S_AXI.w.valid := do_mem_write && mem.req_data.valid
  io.S_AXI.w.bits.data := {
    val wire = mem.req_data.bits.data 
    val width = s_axiDataWidth
    if (dataChunkLimit > 0) Lookup(write_count(log2Up(dataChunkLimit+1)-1, 0), wire(width-1,0),
      ((1 to dataChunkLimit) map (i => ((UInt(i) -> wire((i+1)*width-1,i*width))))).toList)
    else wire
  }
  io.S_AXI.w.bits.last := io.S_AXI.w.valid &&
    (if (dataCountLimit > 0) write_count === UInt(dataCountLimit) else Bool(true))
  mem.req_data.ready := io.S_AXI.w.fire() && 
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
        Mux(mem.req_cmd.valid && !mem.req_cmd.bits.rw, st_read,
        Mux(mem.req_cmd.valid && mem.req_cmd.bits.rw && mem.req_data.valid, 
            st_start_write, st_idle))
    }
    is(st_read) {
      state_r := Mux(io.S_AXI.ar.ready, st_idle, st_read)
    }
    is(st_start_write) {
      state_r := Mux(io.S_AXI.aw.ready, st_write, st_start_write)
    }
    is(st_write) {
      when(io.S_AXI.w.ready && mem.req_data.valid) {
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
}
