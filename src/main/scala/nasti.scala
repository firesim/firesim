package strober

import Chisel._
import junctions.{NASTIMasterIO, NASTISlaveIO, NASTIModule, MemIO, MIFModule}

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

class NASTIShimIO extends Bundle {
  val mnasti = Bundle((new NASTIMasterIO).flip, {case NASTIName => "Master"})
  val snasti = Bundle((new NASTISlaveIO).flip,  {case NASTIName => "Slave"})
  val mAddrBits = mnasti.ar.bits.nastiXAddrBits
  val sAddrBits = snasti.ar.bits.nastiXAddrBits
  val mDataBits = mnasti.r.bits.nastiXDataBits
  val sDataBits = snasti.r.bits.nastiXDataBits
}

class NASTIShim[+T <: SimNetwork](c: =>T) extends MIFModule {
  val io = new NASTIShimIO
  // params
  val addrSizeBits    = params(NASTIAddrSizeBits)
  val resetAddr       = (1 << addrSizeBits) - 1
  val sramRestartAddr = (1 << addrSizeBits) - 2

  val memBlockBytes  = params(MemBlockBytes)
  val memBlockOffset = log2Up(memBlockBytes) 
  val memDataCount   = 8*memBlockBytes / mifDataBits

  val mAddrOffset = log2Up(io.mAddrBits/8)
  val sAddrOffset = scala.math.max(memBlockOffset, log2Up(io.sDataBits/8))

  private val memAddrOffset = mifAddrBits + sAddrOffset
  private val dataCountLimit = (memBlockBytes-1) / (io.sDataBits/8)
  private val dataChunkLimit = (mifDataBits-1) / io.sDataBits
  require(dataCountLimit >= dataChunkLimit && (dataChunkLimit == 0 || (dataCountLimit+1) % (dataChunkLimit+1) == 0),
    "dataCountLimit+1 = %d, dataChunkLimit+1 = %d".format(dataCountLimit+1, dataChunkLimit+1))

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
  val mem = new MemIO // Wire(new MemIO) // connected in the backend
  val st_idle :: st_read :: st_start_write :: st_write :: Nil = Enum(UInt(), 4)
  val state_r = RegInit(st_idle)
  val do_mem_write = state_r === st_write
  val resp_data_buf = Vec.fill(dataChunkLimit){Reg(UInt(width=io.sDataBits))}
  val read_count = RegInit(UInt(0, log2Up(dataChunkLimit+1)))
  val write_count = RegInit(UInt(0, log2Up(dataCountLimit+1)))
  val s_axi_addr = 
    if (memAddrOffset == io.sAddrBits - 4)
      Cat(UInt(1, 4), mem.req_cmd.bits.addr, UInt(0, sAddrOffset))
    else if (memAddrOffset > io.sAddrBits - 4) 
      Cat(UInt(1, 4), mem.req_cmd.bits.addr(io.sAddrBits-4-sAddrOffset-1,0), UInt(0, sAddrOffset))
    else
      Cat(UInt(1, 4), UInt(0, io.sAddrBits-4-memAddrOffset), mem.req_cmd.bits.addr, UInt(0, sAddrOffset))

  mem.req_cmd.ready := 
    (state_r === st_start_write && io.snasti.aw.ready) || (state_r === st_read && io.snasti.ar.ready)

  /* snasti Read Signals */
  // Read Address
  io.snasti.ar.bits.addr := s_axi_addr
  io.snasti.ar.bits.id := mem.req_cmd.bits.tag
  io.snasti.ar.bits.len := UInt(dataCountLimit) // burst length (transfers)
  io.snasti.ar.bits.size := UInt(log2Up(scala.math.min(mifDataBits, io.sDataBits))-3) // burst size (bits/beat)
  io.snasti.ar.valid := state_r === st_read
  // Read Data
  io.snasti.r.ready := mem.resp.ready
  mem.resp.valid := io.snasti.r.valid && 
    (if (dataChunkLimit > 0) read_count === UInt(dataChunkLimit) else Bool(true))
  mem.resp.bits.data := 
    (if (dataChunkLimit > 0) Cat(io.snasti.r.bits.data :: resp_data_buf.toList.reverse) else io.snasti.r.bits.data)
  mem.resp.bits.tag := io.snasti.r.bits.id

  /* snasti Write Signals */
  // Write Address
  io.snasti.aw.bits.addr := s_axi_addr
  io.snasti.aw.bits.id := UInt(0)
  io.snasti.aw.bits.len := io.snasti.ar.bits.len // burst length (transfers)
  io.snasti.aw.bits.size := io.snasti.ar.bits.size // burst size (bits/beat)
  io.snasti.aw.valid := state_r === st_start_write
  // Write Data
  io.snasti.w.valid := do_mem_write && mem.req_data.valid
  io.snasti.w.bits.data := {
    val wire = mem.req_data.bits.data 
    val width = io.sDataBits
    if (dataChunkLimit > 0) Lookup(write_count(log2Up(dataChunkLimit+1)-1, 0), wire(width-1,0),
      ((1 to dataChunkLimit) map (i => ((UInt(i) -> wire((i+1)*width-1,i*width))))).toList)
    else wire
  }
  io.snasti.w.bits.last := io.snasti.w.valid &&
    (if (dataCountLimit > 0) write_count === UInt(dataCountLimit) else Bool(true))
  mem.req_data.ready := io.snasti.w.fire() && 
    (if (dataChunkLimit > 0) write_count(log2Up(dataChunkLimit+1)-1, 0) === UInt(dataChunkLimit) else Bool(true))
  // Write Response
  io.snasti.b.ready := Bool(true)


  if (dataChunkLimit > 0) {
    when(io.snasti.r.fire()) {
      read_count := Mux(read_count === UInt(dataChunkLimit), UInt(0), read_count + UInt(1))
    }
    when(io.snasti.r.valid) {
      resp_data_buf(read_count) := io.snasti.r.bits.data
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
      state_r := Mux(io.snasti.ar.ready, st_idle, st_read)
    }
    is(st_start_write) {
      state_r := Mux(io.snasti.aw.ready, st_write, st_start_write)
    }
    is(st_write) {
      when(io.snasti.w.ready && mem.req_data.valid) {
        if (dataChunkLimit > 0) {
          write_count := write_count + UInt(1)
          when (io.snasti.w.bits.last) {
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
