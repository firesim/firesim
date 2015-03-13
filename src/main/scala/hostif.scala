package strober

import Chisel._
import scala.collection.mutable.{ArrayBuffer, ListBuffer}

class HostIO extends Bundle {
  val axiDataWidth = params(AXIDataWidth)
  val in = Decoupled(UInt(width=axiDataWidth)).flip
  val out = Decoupled(UInt(width=axiDataWidth))
  val count = UInt(OUTPUT, axiDataWidth)
}

trait HasMemData extends Bundle {
  val memDataWidth = params(MemDataWidth) 
  val data = UInt(width=memDataWidth)
}

trait HasMemAddr extends Bundle {
  val memAddrWidth = params(MemAddrWidth)
  val addr = UInt(width=memAddrWidth)
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
class MemAddr extends HasMemAddr

class MemIO extends Bundle {
  val req_cmd  = Decoupled(new MemReqCmd)
  val req_data = Decoupled(new MemData)
  val resp     = Decoupled(new MemResp).flip
}

class StroberHostIF_IO extends Bundle {
  val daisyWidth = params(DaisyWidth)
  val host = new HostIO
  val tmem = (new MemIO).flip
  val fmem = new MemIO
  val daisy = (new DaisyPins(daisyWidth)).flip
  val fire = Bool(OUTPUT)
  val fireNext = Bool(OUTPUT)
  val fifoReady = Bool(INPUT)
}

class StroberHostIF extends Module with HostIFParams {
  val io = new StroberHostIF_IO
  // Host Fifos
  val hostInFifo  = Module(new Queue(UInt(width=axiDataWidth), axiFifoLen))
  val hostOutFifo = Module(new Queue(UInt(width=axiDataWidth), axiFifoLen))
  // Memory State
  val memReqCmd      = Reg(new MemReqCmd)
  val memReqData     = Reg(new MemData)
  val memResp        = Reg(new MemResp)
  val memTag         = RegInit(UInt(0, memTagWidth))
  val memReqCmdFifo  = Module(new Queue(io.fmem.req_cmd.bits.clone, axiFifoLen))
  val memReqDataFifo = Module(new Queue(io.fmem.req_data.bits.clone, axiFifoLen))
  val memRespFifo    = Module(new Queue(io.fmem.resp.bits.clone, axiFifoLen))
  val wAddrTrace     = Module(new Queue(new MemAddr, traceLen))
  val wDataTrace     = Module(new Queue(new MemData, traceLen))
  val rAddrTrace     = Vec.fill(tagNum)(Reg(new MemAddr))
  val rAddrValid     = Vec.fill(tagNum)(RegInit(Bool(false)))

  val fire = Bool()
  val fireNext = RegNext(fire)
  val readNext = RegInit(Bool(false)) // Todo: incorperate this signal to IO readNextording
  io.fire := fire
  io.fireNext := fireNext

  // Host pin connection
  io.host.in  <> hostInFifo.io.enq
  io.host.out <> hostOutFifo.io.deq
  io.host.count := hostOutFifo.io.count
  hostInFifo.io.deq.ready  := Bool(false)
  hostOutFifo.io.enq.valid := Bool(false)
  hostOutFifo.io.enq.bits  := UInt(0)  

  // Target memory pin connection
  memReqCmdFifo.io.enq.bits := Mux(fire, io.tmem.req_cmd.bits, memReqCmd)
  memReqCmdFifo.io.enq.valid := io.tmem.req_cmd.valid && fire
  io.tmem.req_cmd.ready := memReqCmdFifo.io.enq.ready && fire
  memReqDataFifo.io.enq.bits := Mux(fire, io.tmem.req_data.bits, memReqData)
  memReqDataFifo.io.enq.valid := io.tmem.req_data.valid && fire
  io.tmem.req_data.ready := memReqDataFifo.io.enq.ready && fire
  // FPGA memory pin connection
  io.fmem.req_cmd <> memReqCmdFifo.io.deq
  io.fmem.req_data <> memReqDataFifo.io.deq
  memRespFifo.io.enq.bits := io.fmem.resp.bits
  memRespFifo.io.enq.valid := io.fmem.resp.valid
  io.fmem.resp.ready := memRespFifo.io.enq.ready

  // Mem Write Address Trace
  wAddrTrace.io.enq.bits.addr := io.tmem.req_cmd.bits.addr
  wAddrTrace.io.enq.valid     := io.tmem.req_cmd.bits.rw && io.tmem.req_cmd.valid && fireNext
  // wAddrTrace.io.deq.ready     := Bool(false) // TODO: connect it to AXI
  // Mem Write Data Trace
  wDataTrace.io.enq.bits.data := io.tmem.req_data.bits.data
  wDataTrace.io.enq.valid     := io.tmem.req_data.valid && fireNext
  // wDataTrace.io.deq.ready := Bool(false)
  // Turn on Mem Read Address Trace
  when(!io.tmem.req_cmd.bits.rw && memReqCmdFifo.io.enq.valid) {
    val tag = io.tmem.req_cmd.bits.tag
    rAddrTrace(tag).addr := io.tmem.req_cmd.bits.addr
    rAddrValid(tag) := Bool(true)
    memTag := tag + UInt(1)
  }
  // Turn off Mem Read Address Trace 
  when(RegEnable(memRespFifo.io.deq.valid, fire) && fire) {
    rAddrValid(RegEnable(memRespFifo.io.deq.bits.tag, fire)) := Bool(false)
  } 

  // Daisy pin connection
  io.daisy.stall := !fire
  io.daisy.regs.in.bits := UInt(0)
  io.daisy.regs.in.valid := Bool(false)
  io.daisy.regs.out.ready := Bool(false)
  if (Driver.hasSRAM) {
    io.daisy.sram.in.bits := UInt(0)
    io.daisy.sram.in.valid := Bool(false)
    io.daisy.sram.out.ready := Bool(false)
    io.daisy.sram.restart := Bool(false)
  }

  // Machine states
  val sim_IDLE :: sim_STEP :: sim_SNAP1 :: sim_SNAP2 :: sim_TRACE :: sim_MEM :: Nil = Enum(UInt(), 6)
  val simState = RegInit(sim_IDLE)

  val stepcount = RegInit(UInt(0)) // Step Counter
  // Define the fire signal
  fire := stepcount.orR && wAddrTrace.io.enq.ready && wDataTrace.io.enq.ready && io.fifoReady

  val snapbuf   = Reg(UInt(width=axiDataWidth+daisyWidth))
  val snapcount = Reg(UInt(width=log2Up(axiDataWidth+1)))
  val sramcount = Reg(UInt(width=log2Up(Driver.sramMaxSize+1)))
  val snap_IDLE :: snap_READ :: snap_SEND :: Nil = Enum(UInt(), 3)
  val snapState = RegInit(snap_IDLE)

  val raddrcount = Reg(UInt())
  val trace_RCOUNT :: trace_RADDR :: trace_RTAG :: Nil = Enum(UInt(), 3)
  val traceState = RegInit(trace_RCOUNT)

  val addrcount = Reg(UInt())
  val datacount = Reg(UInt())
  val mem_REQ_CMD :: mem_REQ_DATA :: mem_WAIT :: mem_RESP :: Nil = Enum(UInt(), 4)
  val memState = RegInit(mem_REQ_CMD)

  switch(simState) {
    is(sim_IDLE) {
      hostInFifo.io.deq.ready := Bool(true)
      when(hostInFifo.io.deq.fire()) {
        val cmd = hostInFifo.io.deq.bits(cmdWidth-1, 0)
        when(cmd === STEP) {
          readNext  := hostInFifo.io.deq.bits(cmdWidth)
          stepcount := hostInFifo.io.deq.bits(axiDataWidth-1, cmdWidth+1)
          simState  := sim_STEP
        }.elsewhen(cmd === TRACE) {
          simState  := sim_TRACE
        }.elsewhen(cmd === MEM) {
          memReqCmd.rw  := hostInFifo.io.deq.bits(cmdWidth) 
          memReqCmd.tag := memTag
          addrcount := UInt((memAddrWidth-1)/axiDataWidth + 1)
          datacount := UInt((memDataWidth-1)/axiDataWidth + 1)
          simState  := sim_MEM
        }
      }
    }

    is(sim_STEP) {
      when(fire) {
        stepcount := stepcount - UInt(1)
      }.elsewhen(!fireNext) {
        snapcount := UInt(0)
        hostOutFifo.io.enq.bits := UInt(0)
        hostOutFifo.io.enq.valid := Bool(true) 
        when(hostOutFifo.io.enq.fire()) {
          simState := Mux(readNext, sim_SNAP1, sim_IDLE)
          readNext := Bool(false)
        }
        if (Driver.hasSRAM) sramcount := UInt(Driver.sramMaxSize-1)
      }
    }

    // Register Snapshotting
    is(sim_SNAP1) {
      switch(snapState) {
        is(snap_IDLE) {
          snapState := Mux(io.daisy.regs.out.valid, snap_READ, snap_IDLE)
        }
        is(snap_READ) {
          when(snapcount < UInt(axiDataWidth)) {
            io.daisy.regs.out.ready := Bool(true)
            snapbuf := Cat(snapbuf, io.daisy.regs.out.bits)
            snapcount := snapcount + UInt(daisyWidth)
          }.otherwise {
            snapcount := snapcount - UInt(axiDataWidth)
            snapState := snap_SEND
          }
        }
        is(snap_SEND) {
          when(hostOutFifo.io.enq.ready) {
            hostOutFifo.io.enq.bits  := snapbuf >> snapcount
            hostOutFifo.io.enq.valid := Bool(true)
            when (io.daisy.regs.out.valid) {
              snapState := snap_READ
            }.otherwise {
              snapState := snap_IDLE
              if (Driver.hasSRAM) {
                io.daisy.sram.restart := Bool(true)
                simState := sim_SNAP2
              } else {
                simState := sim_IDLE
              }
            }
          }
        }
      }
    }
    // SRAM Snapshotting
    if (Driver.hasSRAM) {
      is(sim_SNAP2) {
        switch(snapState) {
          is(snap_IDLE) {
            snapState := Mux(io.daisy.sram.out.valid, snap_READ, snap_IDLE)
          }
          is(snap_READ) {
            when(snapcount < UInt(axiDataWidth)) {
              io.daisy.sram.out.ready := Bool(true)
              snapbuf := Cat(snapbuf, io.daisy.sram.out.bits)
              snapcount := snapcount + UInt(daisyWidth)
            }.otherwise {
              snapcount := snapcount - UInt(axiDataWidth)
              snapState := snap_SEND
            }
          }
          is(snap_SEND) {
            when(hostOutFifo.io.enq.ready) {
              hostOutFifo.io.enq.bits  := snapbuf >> snapcount
              hostOutFifo.io.enq.valid := Bool(true)
              when (io.daisy.sram.out.valid) {
                snapState := snap_READ
              }.elsewhen (sramcount.orR) {
                sramcount := sramcount - UInt(1)
                io.daisy.sram.restart := Bool(true)
                snapState := snap_IDLE
              }.otherwise {
                snapState := snap_IDLE
                simState := sim_IDLE
              }
            }
          }
        }
      }
    }

    is(sim_TRACE) {
      switch(traceState) {
        is(trace_RCOUNT) {
          hostOutFifo.io.enq.bits := PopCount(rAddrValid)
          hostOutFifo.io.enq.valid := Bool(true)
          when(hostOutFifo.io.enq.fire()) {
            traceState := trace_RADDR
            raddrcount := UInt(tagNum)
          }
        }
        val id = UInt(tagNum) - raddrcount
        is(trace_RADDR) {
          hostOutFifo.io.enq.bits := rAddrTrace(id).addr
          hostOutFifo.io.enq.valid := rAddrValid(id) && raddrcount.orR
          when(!raddrcount.orR) {
            traceState := trace_RCOUNT
            simState := sim_IDLE
          }.elsewhen(hostOutFifo.io.enq.fire()) {
            traceState := trace_RTAG
          }.elsewhen(!hostOutFifo.io.enq.valid) {
            raddrcount := raddrcount - UInt(1)
          }
        }
        is(trace_RTAG) {
          hostOutFifo.io.enq.bits := id
          hostOutFifo.io.enq.valid := Bool(true)
          when(hostOutFifo.io.enq.fire()) {
            traceState := trace_RADDR
            raddrcount := raddrcount - UInt(1)
          }
        }
      }
    }

    is(sim_MEM) {
      switch(memState) {
        is(mem_REQ_CMD) {
          hostInFifo.io.deq.ready := addrcount.orR
          memReqCmdFifo.io.enq.valid := !hostInFifo.io.deq.ready
          when(hostInFifo.io.deq.fire()) {
            memReqCmd.addr := (memReqCmd.addr << UInt(axiDataWidth)) | hostInFifo.io.deq.bits
            addrcount := addrcount - UInt(1)
          }
          when(memReqCmdFifo.io.enq.fire()) {
            memState := Mux(memReqCmd.rw, mem_REQ_DATA, mem_WAIT)
          }
        }
        is(mem_REQ_DATA) {
          hostInFifo.io.deq.ready := datacount.orR
          memReqDataFifo.io.enq.valid := !hostInFifo.io.deq.ready
          when(hostInFifo.io.deq.fire()) {
            memReqData.data := (memReqData.data << UInt(axiDataWidth)) | hostInFifo.io.deq.bits
            datacount := datacount - UInt(1)
          }
          when(memReqDataFifo.io.enq.fire()) {
            memState := mem_REQ_CMD
            simState := sim_IDLE
          }
        }
        is(mem_WAIT) {
          when(io.fmem.resp.fire() && io.fmem.resp.bits.tag === memReqCmd.tag) {
            memRespFifo.io.enq.valid := Bool(false)
            memResp.data := io.fmem.resp.bits.data
            memResp.tag := io.fmem.resp.bits.tag
            memState := mem_RESP
          }
        }
        is(mem_RESP) {
          hostOutFifo.io.enq.valid := datacount.orR
          when(hostOutFifo.io.enq.fire()) {
            hostOutFifo.io.enq.bits := memResp.data(axiDataWidth-1, 0)
            memResp.data := memResp.data >> UInt(axiDataWidth)
            datacount := datacount - UInt(1)
          }.elsewhen(!hostOutFifo.io.enq.valid) {
            memTag := memTag + UInt(1)
            memState := mem_REQ_CMD
            simState := sim_IDLE
          }
        }
      }
    }
  }

  // add custom transforms for daisy chains
  transforms.addTransforms(daisyWidth)
}
