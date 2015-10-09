package strober

import Chisel._
import junctions._

object NASTIShim {
  def apply[T <: Module](c: =>T, targetParams: Parameters = Parameters.empty) = {
    val params = (targetParams alter NASTIParams.mask) alter SimParams.mask
    Module(new NASTIShim(new SimWrapper(c)))(params)
  }
}

class NASTI2Input[T <: Data](gen: T, addr: Int) extends NASTIModule {
  val dataWidth = (gen.flatten.unzip._2 foldLeft 0)(_ + _.needWidth)
  val io = new Bundle {
    val addr = UInt(INPUT, nastiXAddrBits)
    val in = Decoupled(UInt(width=nastiXDataBits)).flip
    val out = Decoupled(gen)
  }
  val s_DATA :: s_DONE :: Nil = Enum(UInt(), 2)
  val state = RegInit(s_DATA)
  val count = RegInit(UInt((dataWidth-1)/nastiXDataBits))
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
        b := (b << UInt(nastiXDataBits)) | io.in.bits
        if (dataWidth > nastiXDataBits) {
          when(count.orR) {
            count := count - UInt(1)
          }.otherwise {
            count := UInt((dataWidth-1)/nastiXDataBits)
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
 
class Output2NASTI[T <: Data](gen: T, addr: Int) extends NASTIModule {
  val dataWidth = (gen.flatten.unzip._2 foldLeft 0)(_ + _.needWidth)
  val io = new Bundle {
    val addr = UInt(INPUT, nastiXAddrBits)
    val in = Decoupled(gen).flip
    val out = Decoupled(UInt(width=nastiXDataBits))
  }
  val s_IDLE :: s_DATA :: Nil = Enum(UInt(), 2)
  val state = RegInit(s_IDLE)
  val count = RegInit(UInt((dataWidth-1)/nastiXDataBits))
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
        if (dataWidth > nastiXDataBits) {
          val b = buf match {
            case p: Packet[_] => p.data
            case b: Bits => b
          }
          b := b.toUInt >> UInt(nastiXDataBits)
          when(count.orR) {
            count := count - UInt(1)
          }.otherwise {
            count := UInt((dataWidth-1)/nastiXDataBits)
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

class NASTISlaveIOMemIOConverter extends MIFModule with NASTIParameters {
  val io = new Bundle {
    val mem = (new MemIO).flip
    val nasti = (new NASTISlaveIO).flip
  }
  val memBlockBytes  = params(MemBlockBytes)
  val addrSizeBits   = params(MemAddrSizeBits)
  val addrOffsetBits = log2Up(scala.math.max(memBlockBytes, nastiXDataBits/8))
  val nastiDataBeats = scala.math.max(1, mifDataBits / nastiXDataBits)

  val st_idle :: st_read :: st_start_write :: st_write :: Nil = Enum(UInt(), 4)
  val state_r = RegInit(st_idle)
  val resp_data_buf = Vec.fill(nastiDataBeats-1){Reg(UInt(width=nastiXDataBits))}
  val (read_count,  read_wrap_out)  = Counter(io.nasti.r.fire(), nastiDataBeats)
  val (write_count, write_wrap_out) = Counter(io.nasti.w.fire(), nastiDataBeats)
  val addr = Wire(UInt(width=addrSizeBits-addrOffsetBits))
  addr := io.mem.req_cmd.bits.addr
  io.mem.req_cmd.ready := 
    (state_r === st_start_write && io.nasti.aw.ready) || (state_r === st_read && io.nasti.ar.ready)

  /* Read Signals */
  // Read Address
  io.nasti.ar.bits.addr := Cat(UInt(1, 4), addr, UInt(0, addrOffsetBits))
  io.nasti.ar.bits.id   := io.mem.req_cmd.bits.tag
  io.nasti.ar.bits.len  := UInt(nastiDataBeats-1) // burst length (transfers)
  io.nasti.ar.bits.size := UInt(log2Up(scala.math.min(mifDataBits/8, nastiXDataBits/8))) // burst size (bits/beat)
  io.nasti.ar.valid     := state_r === st_read 
  // Read Data
  io.nasti.r.ready      := io.mem.resp.ready
  io.mem.resp.bits.tag  := io.nasti.r.bits.id
  io.mem.resp.valid     := read_wrap_out 
  io.mem.resp.bits.data := 
    (if (nastiDataBeats > 1) Cat(io.nasti.r.bits.data, resp_data_buf.toBits) else io.nasti.r.bits.data)

  /* Write Signals */
  // Write Address
  io.nasti.aw.bits.addr := io.nasti.ar.bits.addr
  io.nasti.aw.bits.id   := UInt(0)
  io.nasti.aw.bits.len  := io.nasti.ar.bits.len  // burst length (transfers)
  io.nasti.aw.bits.size := io.nasti.ar.bits.size // burst size (bits/beat)
  io.nasti.aw.valid     := state_r === st_start_write
  // Write Data
  io.mem.req_data.ready := io.nasti.w.bits.last
  io.nasti.w.valid      := io.mem.req_data.valid && state_r === st_write
  io.nasti.w.bits.last  := write_wrap_out
  io.nasti.w.bits.data  := Vec((0 until nastiDataBeats) map (i =>
    io.mem.req_data.bits.data((i+1)*nastiXDataBits-1, i*nastiXDataBits)))(write_count)
  // Write Response
  io.nasti.b.ready := Bool(true)

  if (nastiDataBeats > 1) {
    when(io.nasti.r.valid && !read_wrap_out) {
      resp_data_buf(read_count) := io.nasti.r.bits.data
    }
  }

  /* FSM for memory requests */
  switch(state_r) {
    is(st_idle) {
      state_r := 
        Mux(io.mem.req_cmd.valid && !io.mem.req_cmd.bits.rw, st_read,
        Mux(io.mem.req_cmd.valid && io.mem.req_cmd.bits.rw && io.mem.req_data.valid, 
            st_start_write, st_idle))
    }
    is(st_read) {
      state_r := Mux(io.nasti.ar.ready, st_idle, st_read)
    }
    is(st_start_write) {
      state_r := Mux(io.nasti.aw.ready, st_write, st_start_write)
    }
    is(st_write) {
      state_r := Mux(io.nasti.w.fire() && write_wrap_out, st_idle, st_write)
    }
  }
}


class NASTIShimIO extends Bundle {
  val mnasti = Bundle((new NASTIMasterIO).flip, {case NASTIName => "Master"})
  val snasti = Bundle((new NASTISlaveIO).flip,  {case NASTIName => "Slave"})
  val mAddrBits = mnasti.ar.bits.nastiXAddrBits
  val mDataBits = mnasti.r.bits.nastiXDataBits
}

class NASTIShim[+T <: SimNetwork](c: =>T) extends MIFModule {
  val io = new NASTIShimIO
  // params
  val addrSizeBits    = params(NASTIAddrSizeBits)
  val resetAddr       = (1 << addrSizeBits) - 1
  val sramRestartAddr = (1 << addrSizeBits) - 2

  val mAddrOffset = log2Up(io.mAddrBits/8)
  // Simulation Target
  val sim: T = Module(c)

  // Fake wires for snapshotting
  val snap_out = new Bundle {
    val regs = UInt(OUTPUT, sim.daisyWidth)
    val sram = UInt(OUTPUT, sim.daisyWidth)
    val trace = UInt(OUTPUT, sim.daisyWidth)
    val cntr = UInt(OUTPUT, sim.daisyWidth)
  }
  // Fake wires for accesses from outside FPGA
  val mem_req = new Bundle {
    val addr = UInt(INPUT, mifAddrBits)
    val tag = UInt(INPUT, mifTagBits+1)
    val data = UInt(INPUT, mifDataBits)
  }
  val mem_resp = new Bundle {
    val data = UInt(OUTPUT, mifDataBits)
    val tag = UInt(OUTPUT, mifTagBits)
  }

  /*** mnasti INPUTS ***/
  val waddr_r = RegInit(UInt(0, addrSizeBits))
  val awid_r  = RegInit(UInt(0))
  val st_wr_idle :: st_wr_write :: st_wr_ack :: Nil = Enum(UInt(), 3)
  val st_wr = RegInit(st_wr_idle)
  val do_write = st_wr === st_wr_write
  val reset_t = do_write && (waddr_r === UInt(resetAddr))
  val sram_restart = do_write && (waddr_r === UInt(sramRestartAddr))
  val in_ready = Bool() // Connected in the backend 

  sim.reset := reset_t

  // mnasti Write FSM
  switch(st_wr) {
    is(st_wr_idle) {
      when(io.mnasti.aw.valid && io.mnasti.w.valid) {
        st_wr   := st_wr_write
        waddr_r := io.mnasti.aw.bits.addr >> UInt(mAddrOffset)
        awid_r  := io.mnasti.aw.bits.id
      }
    }
    is(st_wr_write) {
      when(in_ready || waddr_r === UInt(resetAddr) || waddr_r === UInt(sramRestartAddr)) {
        st_wr := st_wr_ack
      } 
    }
    is(st_wr_ack) {
      when(io.mnasti.b.ready) {
        st_wr := st_wr_idle
      }
    }
  }
  io.mnasti.aw.ready    := do_write
  io.mnasti.w.ready     := do_write
  io.mnasti.b.valid     := st_wr === st_wr_ack
  io.mnasti.b.bits.id   := awid_r
  io.mnasti.b.bits.resp := Bits(0)

  /*** mnasti OUTPUS ***/
  val raddr_r = RegInit(UInt(0, addrSizeBits))
  val arid_r  = RegInit(UInt(0))
  val st_rd_idle :: st_rd_read :: Nil = Enum(UInt(), 2)
  val st_rd = RegInit(st_rd_idle)
  val do_read = st_rd === st_rd_read
  val out_data = UInt() // Connected in the backend 
  val out_valid = Bool() // Connected in the backend 

  // mnasti Read FSM
  switch(st_rd) {
    is(st_rd_idle) {
      when(io.mnasti.ar.valid) {
        st_rd   := st_rd_read
        raddr_r := io.mnasti.ar.bits.addr >> UInt(mAddrOffset)
        arid_r  := io.mnasti.ar.bits.id
      }
    }
    is(st_rd_read) {
      when(io.mnasti.r.ready) {
        st_rd   := st_rd_idle
      }
    }
  }
  io.mnasti.ar.ready    := st_rd === st_rd_idle
  io.mnasti.r.valid     := out_valid && do_read
  io.mnasti.r.bits.data := out_data
  io.mnasti.r.bits.last := io.mnasti.r.valid
  io.mnasti.r.bits.id   := arid_r

  /*** snasti ***/
  val memBlockBytes  = params(MemBlockBytes)
  val memBlockOffset = log2Up(memBlockBytes)
  val memDataCount   = 8*memBlockBytes / mifDataBits

  val snasti_mem_conv = Module(new NASTISlaveIOMemIOConverter, {case NASTIName => "Slave"})
  snasti_mem_conv.io.nasti <> io.snasti

  transforms.init(this)
}
