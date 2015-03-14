package strober

import Chisel._
import scala.collection.mutable.ArrayBuffer

object Strober {
  def apply[T <: Module](c: =>T, targetParams: Parameters = Parameters.empty) = {
    val params = targetParams alter StroberParams.mask
    Module(new Strober(c))(params)
  }
}

class HTIFIO extends Bundle {
  val htifLen = params(HTIFLen)
  val in = Decoupled(UInt(width=htifLen)).flip
  val out = Decoupled(UInt(width=htifLen))
}

class HostIO extends Bundle {
  val hostLen = params(HostLen)
  val in = Decoupled(UInt(width=hostLen)).flip
  val out = Decoupled(UInt(width=hostLen))
}

trait HasMemData extends Bundle {
  val memLen = params(MemLen) 
  val data = UInt(width=memLen)
}

trait HasMemAddr extends Bundle {
  val addrLen = params(AddrLen)
  val addr = UInt(width=addrLen)
}

trait HasMemTag extends Bundle {
  val tagLen = params(TagLen)
  val tag = UInt(width=tagLen)
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

class StroberIO extends Bundle {
  val htif = new HTIFIO
  val host = new HostIO
  val mem = new MemIO 
}

class Strober[+T <: Module](c: =>T) extends Module with StroberParams with Commands {
  val io = new StroberIO
  val target = Module(c)
  val stepcount  = RegInit(UInt(0)) 
  val fire       = stepcount.orR
  val fireNext   = RegNext(fire)
  val readNext   = RegInit(Bool(false)) 
  val memReqCmd  = Reg(new MemReqCmd)
  val memReqData = Reg(new MemData)
  val memResp    = Reg(new MemResp)
  val memTag     = RegInit(UInt(0, tagLen))
  val rAddrTrace  = Vec.fill(tagNum)(Reg(new MemAddr))
  val rAddrValid  = Vec.fill(tagNum)(RegInit(Bool(false)))
  val memReqCmdFifo  = Module(new Queue(io.mem.req_cmd.bits.clone, 8))
  val memReqDataFifo = Module(new Queue(io.mem.req_data.bits.clone, 8))
  val memRespFifo    = Module(new Queue(io.mem.resp.bits.clone, 8))
  val (ins: ArrayBuffer[Bits], outs: ArrayBuffer[Bits]) = 
    target.io.flatten.unzip._2 partition (_.dir == INPUT)

  // Find MemIO
  (target.io match {
    case b: Bundle => b.elements find { case (name, wires) => {
      (name == "mem") && (io.mem.flatten forall {case (n0, io0) =>
        wires.flatten exists {case (n1, io1) =>
          n0 == n1 && io0.needWidth == io1.needWidth
        }
      })
    } }
    case _ => None
  }) match {
    case None => {
      memReqCmdFifo.io.enq.valid := Bool(false)
      memReqDataFifo.io.enq.valid := Bool(false)
      memRespFifo.io.deq.ready := Bool(true)
    }
    case Some((n, q)) => q match {
      case b: Bundle => {
        for ((n, i) <- b.elements) {
          i match {
            case dio: DecoupledIO[Data] if n == "req_cmd" => {
              val tMemReqCmd = new MemReqCmd
              tMemReqCmd := dio.bits // to avoid type error......
              memReqCmdFifo.io.enq.bits := Mux(fire, tMemReqCmd, memReqCmd)
              memReqCmdFifo.io.enq.valid := dio.valid && fire
              dio.ready := memReqCmdFifo.io.enq.ready && fire
              when(!tMemReqCmd.rw && memReqCmdFifo.io.enq.valid) {
                // Turn on rAddrTrace
                rAddrTrace(tMemReqCmd.tag).addr := tMemReqCmd.addr
                rAddrValid(tMemReqCmd.tag) := Bool(true)
                // Set memTag's value
                memTag := tMemReqCmd.tag + UInt(1)
              }
            }
            case dio: DecoupledIO[Data] if n == "req_data" => {
              val tMemReqData = new MemData
              tMemReqData := dio.bits // to avoid type error ......!
              memReqDataFifo.io.enq.bits := Mux(fire, tMemReqData, memReqData)
              memReqDataFifo.io.enq.valid := dio.valid && fire
              dio.ready := memReqDataFifo.io.enq.ready && fire
            } 
            case dio: DecoupledIO[Data] if n == "resp" => {
              dio.bits := memRespFifo.io.deq.bits
              dio.valid := memRespFifo.io.deq.valid && fire
              memRespFifo.io.deq.ready := dio.valid && fire
              // Turn off rAddrTrace
              when(RegEnable(memRespFifo.io.deq.valid, fire) && fire) {
                rAddrValid(RegEnable(memRespFifo.io.deq.bits.tag, fire)) := Bool(false)
              }
            }
            case _ => 
          }
        }
        for ((n, io) <- b.flatten) {
          if (io.dir == INPUT)
            ins -= io
          else
            outs -= io
        }
      }
      case _ =>
    }
  }

  // Find HTIF
  (target.io match {
    case b: Bundle => b.elements find { case (name, wires) => {
      (name == "host") && (io.htif.flatten forall {case (n0, io0) =>
        wires.flatten exists {case (n1, io1) =>
          n0 == n1 && io0.needWidth == io1.needWidth
        }
      })
    } }
    case _ => None
  }) match {
    case None =>
    case Some((n, q)) => q match {
       case b: Bundle => {
         for ((n, i) <- b.elements) {
           i match {
             case dio: DecoupledIO[Data] if dio.valid.dir == INPUT => {
               // host_in
               dio.bits <> io.htif.in.bits
               dio.valid := io.htif.in.valid && fire
               io.htif.in.ready := dio.ready && fire
             }
             case dio: DecoupledIO[Data] if dio.valid.dir == OUTPUT => {
               // host_out
               io.htif.out.bits <> dio.bits
               io.htif.out.valid := dio.valid && fire
               dio.ready := io.htif.out.ready && fire
             }
             case _ => // This shouldn't occur
           }
         }
         for ((n, io) <- b.flatten) {
           if (io.dir == INPUT)
             ins -= io
           else
             outs -= io
         }
      }
      case _ =>
    }
  } 

  var id = 0
  val inNum = (ins foldLeft 0)((res, in) => res + (in.needWidth-1)/hostLen + 1)
  val inRegs = Vec.fill(inNum) { Reg(UInt()) }
  for (in <- ins) {
    // Resove width error
    in match {
      case _: Bool => inRegs(id).init("", 1)
      case _ => 
    }
    val width = in.needWidth
    val n = (width-1) / hostLen + 1
    val regs = (0 until n) map { x => inRegs(id+x) }
    in := Mux(fire, Cat(regs), UInt(0))
    id += n
  }

  id = 0
  val outNum = (outs foldLeft 0)((res, out) => res + (out.needWidth-1)/hostLen + 1)
  val outRegs = Vec.fill(outNum) { Reg(UInt()) }
  for (out <- outs) {
    out match {
      case _: Bool => outRegs(id).init("", 1)
      case _ => 
    }
    val width = out.needWidth
    val n = (width-1) / hostLen + 1
    val regs = (0 until n) map { x => outRegs(id+x) }
    for ((reg, x) <- regs.zipWithIndex) {
      val low = x * hostLen
      val high = math.min(hostLen-1+low, width-1)
      reg := Mux(fireNext, out(high, low), reg)
    }
    id += n
  }

  // Host pin connection
  io.host.in.ready  := Bool(false)
  io.host.out.valid := Bool(false)
  io.host.out.bits  := UInt(0)

  // Memory pin connection
  io.mem.req_cmd <> memReqCmdFifo.io.deq
  io.mem.req_data <> memReqDataFifo.io.deq
  memRespFifo.io.enq.bits := io.mem.resp.bits
  memRespFifo.io.enq.valid := io.mem.resp.valid
  io.mem.resp.ready := memRespFifo.io.enq.ready

  // Daisy pin connection
  val daisy = addDaisyPins(target, daisyLen)
  daisy.stall := !fire 
  daisy.regs.in.bits := UInt(0)
  daisy.regs.in.valid := Bool(false)
  daisy.regs.out.ready := Bool(false)
  if (Driver.hasSRAM) {
    daisy.sram.in.bits := UInt(0)
    daisy.sram.in.valid := Bool(false)
    daisy.sram.out.ready := Bool(false)
    daisy.sram.restart := Bool(false)
  }

  // Machine states
  val (sim_IDLE :: sim_STEP :: sim_SNAP1 :: sim_SNAP2 :: sim_TRACE :: 
       sim_POKE :: sim_PEEK :: sim_MEM :: Nil) = Enum(UInt(), 8)
  val simState = RegInit(sim_IDLE)

  val snapbuf   = Reg(UInt(width=hostLen+daisyLen))
  val snapcount = Reg(UInt(width=log2Up(hostLen+1)))
  val sramcount = Reg(UInt(width=log2Up(Driver.sramMaxSize+1)))
  val snap_IDLE :: snap_READ :: snap_SEND :: Nil = Enum(UInt(), 3)
  val snapState = RegInit(snap_IDLE)

  val pokecount = Reg(UInt())
  val peekcount = Reg(UInt())

  val waddrcount = Reg(UInt(width=log2Up(addrLen+1)))
  val wdatacount = Reg(UInt(width=log2Up(memLen+1)))
  val raddrcount = Reg(UInt())
  val trace_COUNT :: trace_ADDR :: trace_TAG :: Nil = Enum(UInt(), 3)
  val traceState = RegInit(trace_COUNT)

  val addrcount = Reg(UInt())
  val datacount = Reg(UInt())
  val mem_REQ_CMD :: mem_REQ_DATA :: mem_WAIT :: mem_RESP :: Nil = Enum(UInt(), 4)
  val memState = RegInit(mem_REQ_CMD)

  switch(simState) {
    is(sim_IDLE) {
      io.host.in.ready := Bool(true)
      when(io.host.in.fire()) {
        val cmd = io.host.in.bits(cmdLen-1, 0)
        when(cmd === STEP) {
          readNext := io.host.in.bits(cmdLen)
          stepcount := io.host.in.bits(hostLen-1, cmdLen+1)
          simState := sim_STEP
        }.elsewhen(cmd === POKE) {
          pokecount := UInt(inNum)
          simState := sim_POKE
        }.elsewhen(cmd === PEEK) {
          peekcount := UInt(outNum)
          simState := sim_PEEK
        }.elsewhen(cmd === TRACE) {
          simState := sim_TRACE
        }.elsewhen(cmd === MEM) {
          memReqCmd.rw := io.host.in.bits(cmdLen) 
          memReqCmd.tag := memTag
          addrcount := UInt((addrLen-1)/hostLen + 1)
          datacount := UInt((memLen-1)/hostLen + 1)
          simState := sim_MEM
        }
      }
    }

    is(sim_STEP) {
      when(fire) {
        stepcount := stepcount - UInt(1)
      }.elsewhen(!fireNext) {
        snapcount := UInt(0)
        io.host.out.bits := UInt(0)
        io.host.out.valid := Bool(true) 
        when(io.host.out.fire()) {
          simState := Mux(readNext, sim_SNAP1, sim_IDLE)
          readNext := Bool(false)
        }
        if (Driver.hasSRAM) sramcount := UInt(Driver.sramMaxSize-1)
      }
    }

    // Snapshoting inputs and registers
    is(sim_SNAP1) {
      switch(snapState) {
        is(snap_IDLE) {
          snapState := Mux(daisy.regs.out.valid, snap_READ, snap_IDLE)
        }
        is(snap_READ) {
          when(snapcount < UInt(hostLen)) {
            daisy.regs.out.ready := Bool(true)
            snapbuf := Cat(snapbuf, daisy.regs.out.bits)
            snapcount := snapcount + UInt(daisyLen)
          }.otherwise {
            snapcount := snapcount - UInt(hostLen)
            snapState := snap_SEND
          }
        }
        is(snap_SEND) {
          when(io.host.out.ready) {
            io.host.out.bits  := snapbuf >> snapcount
            io.host.out.valid := Bool(true)
            when (daisy.regs.out.valid) {
              snapState := snap_READ
            }.otherwise {
              snapState := snap_IDLE
              if (Driver.hasSRAM) {
                daisy.sram.restart := Bool(true)
                simState := sim_SNAP2
              } else {
                simState := sim_IDLE
              }
            }
          }
        }
      }
    }
    // Snapshotring SRAMs
    if (Driver.hasSRAM) {
      is(sim_SNAP2) {
        switch(snapState) {
          is(snap_IDLE) {
            snapState := Mux(daisy.sram.out.valid, snap_READ, snap_IDLE)
          }
          is(snap_READ) {
            when(snapcount < UInt(hostLen)) {
              daisy.sram.out.ready := Bool(true)
              snapbuf := Cat(snapbuf, daisy.sram.out.bits)
              snapcount := snapcount + UInt(daisyLen)
            }.otherwise {
              snapcount := snapcount - UInt(hostLen)
              snapState := snap_SEND
            }
          }
          is(snap_SEND) {
            when(io.host.out.ready) {
              io.host.out.bits  := snapbuf >> snapcount
              io.host.out.valid := Bool(true)
              when (daisy.sram.out.valid) {
                snapState := snap_READ
              }.elsewhen (sramcount.orR) {
                sramcount := sramcount - UInt(1)
                daisy.sram.restart := Bool(true)
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

    is(sim_POKE) {
      val id = UInt(inNum) - pokecount
      io.host.in.ready := pokecount.orR
      when(io.host.in.fire()) {
        inRegs(id) := io.host.in.bits 
        pokecount := pokecount - UInt(1)
      }.elsewhen(!io.host.in.ready) {
        simState := sim_IDLE
      }
    }
    is(sim_PEEK) {
      val id = UInt(outNum) - peekcount
      io.host.out.bits  := outRegs(id)
      io.host.out.valid := peekcount.orR
      when(io.host.out.fire()) {
        peekcount := peekcount - UInt(1)
      }.elsewhen(!io.host.out.valid) {
        simState := sim_IDLE
      }
    }

    is(sim_TRACE) {
      switch(traceState) {
        is(trace_COUNT) {
          io.host.out.bits := PopCount(rAddrValid)
          io.host.out.valid := Bool(true)
          when(io.host.out.fire()) {
            traceState := trace_ADDR
            raddrcount := UInt(tagNum)
          }
        }
        val id = UInt(tagNum) - raddrcount
        is(trace_ADDR) {
          io.host.out.bits := rAddrTrace(id).addr
          io.host.out.valid := rAddrValid(id) && raddrcount.orR
          when(!raddrcount.orR) {
            traceState := trace_COUNT
            simState := sim_IDLE
          }.elsewhen(io.host.out.fire()) {
            traceState := trace_TAG
          }.elsewhen(!io.host.out.valid) {
            raddrcount := raddrcount - UInt(1)
          }
        }
        is(trace_TAG) {
          io.host.out.bits := id
          io.host.out.valid := Bool(true)
          when(io.host.out.fire()) {
            traceState := trace_ADDR
            raddrcount := raddrcount - UInt(1)
          }
        }
      }
    }

    is(sim_MEM) {
      switch(memState) {
        is(mem_REQ_CMD) {
          io.host.in.ready := addrcount.orR
          memReqCmdFifo.io.enq.valid := !io.host.in.ready
          when(io.host.in.fire()) {
            memReqCmd.addr := (memReqCmd.addr << UInt(hostLen)) | io.host.in.bits
            addrcount := addrcount - UInt(1)
          }
          when(memReqCmdFifo.io.enq.fire()) {
            memState := Mux(memReqCmd.rw, mem_REQ_DATA, mem_WAIT)
          }
        }
        is(mem_REQ_DATA) {
          io.host.in.ready := datacount.orR
          memReqDataFifo.io.enq.valid := !io.host.in.ready
          when(io.host.in.fire()) {
            memReqData.data := (memReqData.data << UInt(hostLen)) | io.host.in.bits
            datacount := datacount - UInt(1)
          }
          when(memReqDataFifo.io.enq.fire()) {
            memState := mem_REQ_CMD
            simState := sim_IDLE
          }
        }
        is(mem_WAIT) {
          when(io.mem.resp.fire() && io.mem.resp.bits.tag === memReqCmd.tag) {
            memRespFifo.io.enq.valid := Bool(false)
            memResp.data := io.mem.resp.bits.data
            memResp.tag := io.mem.resp.bits.tag
            memState := mem_RESP
          }
        }
        is(mem_RESP) {
          io.host.out.valid := datacount.orR
          when(io.host.out.fire()) {
            io.host.out.bits := memResp.data(hostLen-1, 0)
            memResp.data := memResp.data >> UInt(hostLen)
            datacount := datacount - UInt(1)
          }.elsewhen(!io.host.out.valid) {
            memTag := memTag + UInt(1)
            memState := mem_REQ_CMD
            simState := sim_IDLE
          }
        }
      }
    }
  }

  // add custom transforms for daisy chains
  transforms.addTransforms(daisyLen)
}
