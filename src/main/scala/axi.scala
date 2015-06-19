package strober

import Chisel._
import scala.collection.mutable.{ArrayBuffer, ListBuffer}

object SimAXI4Wrapper {
  def apply[T <: Module](c: =>T, targetParams: Parameters = Parameters.empty) = {
    val params = (targetParams alter AXI4Params.mask) // alter SimParams.mask
    Module(new SimAXI4Wrapper(new SimWrapper(c)))(params)
  }
}

class AXI4Addr extends Bundle {
  // Write / Read Address Channel Signals
  val axiAddrWidth = params(AXIAddrWidth)
  val id    = Bits(width=12)
  val addr  = Bits(width=axiAddrWidth)
  val len   = Bits(width=8)
  val size  = Bits(width=3)
  val burst = Bits(width=2)
}

class AXI4Read extends Bundle {
  // Read Data Channel Signals
  val axiDataWidth = params(AXIDataWidth)
  val data   = Bits(width=axiDataWidth)
  val last   = Bool()
  val id     = Bits(width=12)
  val resp   = Bits(width=2)
}

class AXI4Write extends Bundle {
  // Write Data Channel Signals
  val axiDataWidth = params(AXIDataWidth)
  val data   = Bits(width=axiDataWidth)
  val last   = Bool()
  val strb   = Bits(width=4)
}

class AXI4Resp extends Bundle {
  // Write Response Channel Signals
  val id     = Bits(width=12)
  val resp   = Bits(width=2) // Not used here
}

class AXI4_IO extends Bundle { // S_AXI
  val aw = Decoupled(new AXI4Addr)
  val w  = Decoupled(new AXI4Write)
  val b  = Decoupled(new AXI4Resp).flip
  val ar = Decoupled(new AXI4Addr)
  val r  = Decoupled(new AXI4Read).flip
}

class AXI4 extends Bundle {
  val M_AXI = (new AXI4_IO).flip
  val S_AXI = new AXI4_IO
}

class AXI2Channel[T <: Bits](gen: Packet[T], addr: Int) extends Module {
  val axiAddrWidth = params(AXIAddrWidth)
  val axiDataWidth = params(AXIDataWidth)
  val dataWidth = gen.data.needWidth
  val io = new Bundle {
    val addr = UInt(INPUT, axiAddrWidth)
    val in = Decoupled(UInt(width=axiDataWidth)).flip
    val out = Decoupled(gen)
  }
  val s_HEADER :: s_DATA :: s_DONE :: Nil = Enum(UInt(), 3)
  val state = RegInit(s_DATA)
  val count = RegInit(UInt((dataWidth-1)/axiDataWidth))
  val buf = Reg(gen)
 
  io.in.ready := io.out.ready
  io.out.bits := buf
  io.out.valid := state === s_DONE

  switch(state) {
    /*
    is(s_HEADER) {
      when(io.in.fire()) {
        buf.header := in.bits
        count := UInt((dataWidth-1)/axiWidth)
        state := s_DATA
      }
    }
    */
    is(s_DATA) {
      when(io.in.fire() && io.addr === UInt(addr)) {
        buf.data := (buf.data << UInt(axiDataWidth)) | io.in.bits
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
      state := s_DATA
    }
  }
}
 
class Channel2AXI[T <: Bits](gen: Packet[T], addr: Int) extends Module {
  val axiAddrWidth = params(AXIAddrWidth)
  val axiDataWidth = params(AXIDataWidth)
  val dataWidth = gen.data.needWidth
  val io = new Bundle {
    val addr = UInt(INPUT, axiAddrWidth)
    val in = Decoupled(gen).flip
    val out = Decoupled(UInt(width=axiDataWidth))
  }
  val s_IDLE :: s_HEADER :: s_DATA :: Nil = Enum(UInt(), 3)
  val state = RegInit(s_IDLE)
  val count = RegInit(UInt((dataWidth-1)/axiDataWidth))
  val buf = Reg(gen)

  io.in.ready := state === s_IDLE
  io.out.bits := buf.data
  io.out.valid := state === s_DATA

  switch(state) {
    is(s_IDLE) {
      state := Mux(io.in.fire(), s_DATA, s_IDLE)
      buf := io.in.bits
    }
    /*
    is(s_HEADER) {
      when(io.out.ready) {
        io.out.bits := buf.header
        io.out.valid := Bool(true)
        count := UInt((dataWidth-1)/axiDataWidth)
        state := s_DATA
      }
    }
    */
    is(s_DATA) {
      when(io.out.ready && io.addr === UInt(addr)) {
        if (dataWidth > axiDataWidth) {
          buf.data := buf.data.toUInt >> UInt(axiDataWidth)
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
  val sim: T = Module(c)
  val io = new AXI4
  val axiAddrWidth = params(AXIAddrWidth)
  val axiDataWidth = params(AXIDataWidth)
  val addrRegWidth = params(AddrRegWidth)
  val resetAddr = params(ResetAddr)

  /*** M_AXI INPUTS ***/
  val waddr_r = RegInit(UInt(0, addrRegWidth))
  val awid_r  = RegInit(UInt(0))
  val st_wr_idle::st_wr_write::st_wr_ack :: Nil = Enum(UInt(), 3)
  val st_wr = RegInit(st_wr_idle)
  val do_write = st_wr === st_wr_write
  val reset_t = do_write && (waddr_r === UInt(resetAddr))
  sim.reset := reset_t

  val in_convs = sim.io.ins.zipWithIndex map { case (in, i) =>
    val converter = Module(new AXI2Channel(in.bits, i))
    converter.reset := reset_t
    converter.io.addr := waddr_r
    converter.io.in.bits := io.M_AXI.w.bits.data
    converter.io.in.valid := do_write
    converter.io.out <> in
    converter
  }

  val in_ready = Vec.fill(in_convs.size){Bool()}
  in_ready.zipWithIndex foreach { case (ready, i) => ready := in_convs(i).io.in.ready }

  // M_AXI Write FSM
  switch(st_wr) {
    is(st_wr_idle) {
      when(io.M_AXI.aw.valid && io.M_AXI.w.valid) {
        st_wr   := st_wr_write
        waddr_r := io.M_AXI.aw.bits.addr >> UInt(2)
        awid_r  := io.M_AXI.aw.bits.id
      }
    }
    is(st_wr_write) {
      when(in_ready(waddr_r) || waddr_r === UInt(resetAddr)) {
        st_wr := st_wr_ack
      } 
    }
    is(st_wr_ack) {
      when(io.M_AXI.b.ready) {
        st_wr := st_wr_idle
      }
    }
  }
  io.M_AXI.aw.ready  := do_write
  io.M_AXI.w.ready   := do_write
  io.M_AXI.b.valid   := st_wr === st_wr_ack
  io.M_AXI.b.bits.id := awid_r

  /*** M_AXI OUTPUS ***/
  val raddr_r = RegInit(UInt(0, addrRegWidth))
  val arid_r  = RegInit(UInt(0))
  val st_rd_idle :: st_rd_read :: Nil = Enum(UInt(), 2)
  val st_rd = RegInit(st_rd_idle)
  val do_read = st_rd === st_rd_read  

  val out_convs = sim.io.outs.zipWithIndex map { case (out, i) =>
    val converter = Module(new Channel2AXI(out.bits, i))
    converter.reset := reset_t
    out <> converter.io.in
    converter.io.out.ready := do_read && io.M_AXI.r.ready 
    converter.io.addr := raddr_r
    converter
  }

  val out_data = Vec.fill(out_convs.size){UInt()}
  val out_valid = Vec.fill(out_convs.size){Bool()}
  out_data.zipWithIndex foreach { case (data, i) => data := out_convs(i).io.out.bits }
  out_valid.zipWithIndex foreach { case (valid, i) => valid := out_convs(i).io.out.valid }

  // M_AXI Read FSM
  switch(st_rd) {
    is(st_rd_idle) {
      when(io.M_AXI.ar.valid) {
        st_rd   := st_rd_read
        raddr_r := io.M_AXI.ar.bits.addr >> UInt(2)
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
  io.M_AXI.r.valid     := do_read && out_valid(raddr_r) 
  io.M_AXI.r.bits.last := io.M_AXI.r.valid
  io.M_AXI.r.bits.id   := arid_r
  io.M_AXI.r.bits.data := out_data(raddr_r)

  transforms.init(this)
}
