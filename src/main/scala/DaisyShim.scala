package daisy

import Chisel._
import scala.collection.mutable.ArrayBuffer

object DaisyShim {
  def apply[T <: Module](c: =>T, targetParams: Parameters = Parameters.empty) = {
    val params = targetParams alter daisyParams.mask
    Module(new DaisyShim(c))(params)
  }
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

class DaisyShimIO extends Bundle {
  val host = new HostIO
  val mem = new MemIO 
}

class DaisyShim[+T <: Module](c: =>T) extends Module with DaisyShimParams with DebugCommands {
  val io = new DaisyShimIO
  val target = Module(c)
  val dIns = ArrayBuffer[DecoupledIO[Data]]()
  val dOuts = ArrayBuffer[DecoupledIO[Data]]()
  val wIns = ArrayBuffer[Bits]()
  val wOuts = ArrayBuffer[Bits]()
  def findIOs[T <: Data](io: T, name: String = "") {
    io match {
      case dIO: DecoupledIO[Data] => {
        if (dIO.valid.dir == INPUT) dIns += dIO else dOuts += dIO
      }
      case b: Bundle => {
        for ((n, elm) <- b.elements) {
          findIOs(elm, n)
        }
      }
      case _ => {
        val (ins, outs) = io.flatten partition (_._2.dir == INPUT)
        wIns ++= ins.unzip._2
        wOuts ++= outs.unzip._2
      }
    }
  }
  findIOs(target.io)

  val fire = Bool()
  val record = RegInit(Bool(false)) // Todo: incorperate this signal to IO recordording

  // For memory commands
  val memReqCmd   = Reg(new MemReqCmd)
  val memReqData  = Reg(new MemData)
  val memResp     = Reg(new MemResp)
  val memTag      = RegInit(UInt(0, tagLen))
  val memReqCmdQ  = Module(new Queue(io.mem.req_cmd.bits.clone, traceLen))
  val memReqDataQ = Module(new Queue(io.mem.req_data.bits.clone, traceLen))
  val memRespQ    = Module(new Queue(io.mem.resp.bits.clone, traceLen))
  val wAddrTrace  = Module(new Queue(new MemAddr, traceLen))
  val wDataTrace  = Module(new Queue(new MemData, traceLen))
  val rAddrTrace  = Vec.fill(tagNum)(Reg(new MemAddr))
  val rAddrValid  = Vec.fill(tagNum)(RegInit(Bool(false)))

  // Find the target's MemIO
  // todo: extend multi mem ports
  (dOuts find { wires =>
    val hostNames = io.mem.req_cmd.flatten.unzip._1
    val targetNames = wires.flatten.unzip._1.toSet
    hostNames forall (targetNames contains _)
  }) match {
    case None => {
      memReqCmdQ.io.enq.bits := memReqCmd
      memReqCmdQ.io.enq.valid := Bool(false)
      wAddrTrace.io.enq.valid := Bool(false)
    }
    case Some(q) => {
      val tMemReqCmd = new MemReqCmd
      tMemReqCmd := q.bits // to avoid type error......
      memReqCmdQ.io.enq.bits := Mux(fire, tMemReqCmd, memReqCmd)
      memReqCmdQ.io.enq.valid := q.valid && fire
      q.ready := memReqCmdQ.io.enq.ready && fire
      // Trace write addr
      wAddrTrace.io.enq.bits.addr := tMemReqCmd.addr
      wAddrTrace.io.enq.valid := tMemReqCmd.rw && q.valid && fire
      // Turn on rAddrTrace
      when(!tMemReqCmd.rw && memReqCmdQ.io.enq.valid) {
        rAddrTrace(tMemReqCmd.tag).addr := tMemReqCmd.addr
        rAddrValid(tMemReqCmd.tag) := Bool(true)   
      }
      dOutNum -= 1
      dOuts -= q
    }
  }
  wAddrTrace.io.deq.ready := Bool(false)

  (dOuts find { wires =>
    val hostNames = io.mem.req_data.flatten.unzip._1
    val targetNames = wires.flatten.unzip._1.toSet
    hostNames forall (targetNames contains _)
  }) match {
    case None => {
      memReqDataQ.io.enq.bits := memReqData
      memReqDataQ.io.enq.valid := Bool(false)
      wDataTrace.io.enq.valid := Bool(false)
    }
    case Some(q) => {
      val tMemReqData = new MemData
      tMemReqData := q.bits // to avoid type error ......!
      memReqDataQ.io.enq.bits := Mux(fire, tMemReqData, memReqData)
      memReqDataQ.io.enq.valid := q.valid && fire
      q.ready := memReqDataQ.io.enq.ready && fire
      // Trace write data
      wDataTrace.io.enq.bits.data := tMemReqData.data
      wDataTrace.io.enq.valid := q.valid && fire
      dOutNum -= 1
      dOuts -= q
    }
  }
  wDataTrace.io.deq.ready := Bool(false)
 
  (dIns find { wires =>
    val hostNames = io.mem.resp.flatten.unzip._1
    val targetNames = wires.flatten.unzip._1.toSet
    hostNames forall (targetNames contains _)
  }) match {
    case None => {
      memRespQ.io.deq.ready := Bool(true)
    }
    case Some(q) => {
      q.bits := memRespQ.io.deq.bits
      q.valid := memRespQ.io.deq.valid && fire
      memRespQ.io.deq.ready := q.ready && fire
      // Turn off rAddrTrace
      when(q.valid) {
        rAddrValid(memRespQ.io.deq.bits.tag) := Bool(false)
      }
      dInNum -= 1
      dIns -= q
    }
  }

  /*** IO Recording ***/
  // For decoupled IOs, insert FIFOs
  var dInNum = 0
  val dInQs = ArrayBuffer[Queue[UInt]]() 
  var id = 0
  for (in <- dIns) {
    var valid = Bool(true)
    for ((_, io) <- in.bits.flatten) {
      val width = io.needWidth
      val n = (width-1) / hostLen + 1
      val qs = ArrayBuffer[Queue[UInt]]()
      for (i <- 0 until n) {
        val low = i * hostLen
        val high = math.min(hostLen-1+low, width-1)  
        val q = Module(new Queue(UInt(width=high-low+1), traceLen))
        q.io.deq.ready := in.ready && fire 
        qs += q
      }
      io := Cat(qs map (_.io.deq.bits))
      valid = valid && (qs.tail foldLeft qs.head.io.deq.valid)(_ && _.io.deq.valid)
      id += n
      dInNum += n
      dInQs ++= qs
      qs.clear
    }
    in.valid := fire && valid
  }
  val dInEnqs = Vec(dInQs map (_.io.enq.clone.flip))
  dInQs.zipWithIndex foreach { case (q, id) => dInEnqs(id) <> q.io.enq }
  dInEnqs foreach (_.bits := Bits(0))
  dInEnqs foreach (_.valid := Bool(false))

  var dOutNum = 0
  val dOutQs = ArrayBuffer[Queue[UInt]]() 
  id = 0
  for (out <- dOuts) {
    var ready = Bool(true)
    for ((_, io) <- out.bits.flatten) {
      val width = io.needWidth
      val n = (width-1) / hostLen + 1
      val qs = ArrayBuffer[Queue[UInt]]()
      for (i <- 0 until n) {
        val low = i * hostLen
        val high = math.min(hostLen-1+low, width-1)  
        val q = Module(new Queue(UInt(width=high-low+1), traceLen))
        q.io.enq.bits := io(high, low)
        q.io.enq.valid := out.valid && fire
        qs += q
      }
      ready = ready && (qs.tail foldLeft qs.head.io.enq.ready)(_ && _.io.enq.ready)
      id += n
      dOutNum += n
      dOutQs ++= qs
      qs.clear
    }
    out.ready := fire && ready
  }
  val dOutDeqs = Vec(dOutQs map (_.io.deq.clone))
  val dOutCnts = Vec(dOutQs map (_.io.count.clone))
  dOutQs.zipWithIndex foreach { case (q, id) => q.io.deq <> dOutDeqs(id) }
  dOutQs.zipWithIndex foreach { case (q, id) => q.io.count <> dOutCnts(id) }
  dOutDeqs foreach (_.ready := Bool(false))

  // For wire IOs, insert FFs
  val wInNum = (wIns foldLeft 0)((res, in) => res + (in.needWidth-1)/hostLen + 1)
  val wInFFs = Vec.fill(wInNum) { Reg(UInt()) }
  id = 0
  for (in <- wIns) {
    // Resove width error
    in match {
      case _: Bool => wInFFs(id).init("", 1)
      case _ => 
    }
    val width = in.needWidth
    val n = (width-1) / hostLen + 1
    val ffs = (0 until n) map { x => wInFFs(id+x) }
    in := Mux(fire, Cat(ffs), UInt(0))
    id += n
  }

  val wOutNum = (wOuts foldLeft 0)((res, out) => res + (out.needWidth-1)/hostLen + 1)
  val wOutFFs = Vec.fill(wOutNum) { Reg(UInt()) }
  val fireNext = RegNext(fire)
  id = 0
  for (out <- wOuts) {
    out match {
      case _: Bool => wOutFFs(id).init("", 1)
      case _ => 
    }
    val width = out.needWidth
    val n = (width-1) / hostLen + 1
    val ffs = (0 until n) map { x => wOutFFs(id+x) }
    for ((ff, x) <- ffs.zipWithIndex) {
      val low = x * hostLen
      val high = math.min(hostLen-1+low, width-1)
      ff := Mux(fireNext, out(high, low), ff)
    }
    id += n
  }

  // Host pins
  io.host.in.ready  := Bool(false)
  io.host.out.valid := Bool(false)
  io.host.out.bits  := UInt(0)

  // Daisy pins
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

  // Memory Pins
  io.mem.req_cmd <> memReqCmdQ.io.deq
  io.mem.req_data <> memReqDataQ.io.deq
  memRespQ.io.enq.bits := io.mem.resp.bits
  memRespQ.io.enq.valid := io.mem.resp.valid
  io.mem.resp.ready := memRespQ.io.enq.ready

  // Machine states
  val (debug_IDLE :: debug_STEP :: debug_SNAP1 :: debug_SNAP2 :: debug_TRACE :: 
       debug_POKE :: debug_PEEK :: debug_POKED :: debug_PEEKD :: debug_MEM :: Nil) = Enum(UInt(), 10)
  val debugState = RegInit(debug_IDLE)

  val stepcount = RegInit(UInt(0)) // Step Counter
  // Define the fire signal
  fire := (dOutQs foldLeft stepcount.orR)(_ && _.io.enq.ready) &&
          wAddrTrace.io.enq.ready && wDataTrace.io.enq.ready &&
          debugState === debug_STEP

  val snapbuf   = Reg(UInt(width=hostLen+daisyLen))
  val snapcount = Reg(UInt(width=log2Up(hostLen+1)))
  val sramcount = Reg(UInt(width=log2Up(Driver.sramMaxSize+1)))
  val snap_IDLE :: snap_READ :: snap_SEND :: Nil = Enum(UInt(), 3)
  val snapState = RegInit(snap_IDLE)

  val pokecount = Reg(UInt())
  val peekcount = Reg(UInt())

  val pokedlen = Reg(UInt(width=log2Up(traceLen)))
  val pokedcount = Reg(UInt())
  val poked_COUNT :: poked_DATA :: Nil = Enum(UInt(), 2)
  val pokedState = RegInit(poked_COUNT)

  val peekdcount = Reg(UInt())
  val peekd_COUNT :: peekd_DATA :: Nil = Enum(UInt(), 2)
  val peekdState = RegInit(peekd_COUNT)

  val raddrcount = Reg(UInt())
  val trace_WCOUNT :: trace_WADDR :: trace_WDATA :: trace_RCOUNT :: trace_RADDR :: Nil = Enum(UInt(), 5)
  val traceState = RegInit(trace_WCOUNT)

  val addrcount = Reg(UInt())
  val datacount = Reg(UInt())
  val mem_REQ_CMD :: mem_REQ_DATA :: mem_WAIT :: mem_RESP :: Nil = Enum(UInt(), 4)
  val memState = RegInit(mem_REQ_CMD)

  switch(debugState) {
    is(debug_IDLE) {
      io.host.in.ready := Bool(true)
      when(io.host.in.fire()) {
        val cmd = io.host.in.bits(cmdLen-1, 0)
        when(cmd === STEP) {
          record := io.host.in.bits(cmdLen)
          stepcount := io.host.in.bits(hostLen-1, cmdLen+1)
          debugState := debug_STEP
        }.elsewhen(cmd === POKE) {
          pokecount := UInt(wInNum)
          debugState := debug_POKE
        }.elsewhen(cmd === PEEK) {
          peekcount := UInt(wOutNum)
          debugState := debug_PEEK
        }.elsewhen(cmd === POKED) {
          pokedcount := UInt(dInNum)
          debugState := debug_POKED
        }.elsewhen(cmd === PEEKD) {
          pokedcount := UInt(dOutNum)
          debugState := debug_PEEKD
        }.elsewhen(cmd === TRACE) {
          debugState := debug_TRACE
        }.elsewhen(cmd === MEM) {
          memReqCmd.rw := io.host.in.bits(cmdLen) 
          memReqCmd.tag := memTag
          addrcount := UInt((addrLen-1)/hostLen + 1)
          datacount := UInt((memLen-1)/hostLen + 1)
          debugState := debug_MEM
        }
      }
    }

    is(debug_STEP) {
      when(fire) {
        stepcount := stepcount - UInt(1)
      }.otherwise {
        when(stepcount.orR) {
          when(!(wAddrTrace.io.enq.ready && wDataTrace.io.enq.ready)) {
            io.host.out.bits := step_TRACE
            io.host.out.valid := Bool(true)
            when(io.host.out.fire()) {
              debugState := debug_TRACE 
            } 
          }.otherwise {
            io.host.out.bits := step_PEEKD
            io.host.out.valid := Bool(true)
            peekdcount := UInt(dOutNum)
            when(io.host.out.fire()) {
              debugState := debug_PEEKD
            }
          }
        }.elsewhen(RegNext(!stepcount.orR)) {
          snapcount := UInt(0)
          if (Driver.hasSRAM) { sramcount := UInt(Driver.sramMaxSize-1) }
          io.host.out.bits := step_FIN
          io.host.out.valid := Bool(true) 
          when(io.host.out.fire()) {
            debugState := Mux(record, debug_SNAP1, debug_IDLE)
            record := Bool(false)
          }
        }
      }
    }

    // Snapshoting inputs and registers
    is(debug_SNAP1) {
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
                debugState := debug_SNAP2
              } else {
                debugState := debug_IDLE
              }
            }
          }
        }
      }
    }
    // Snapshotring SRAMs
    if (Driver.hasSRAM) {
      is(debug_SNAP2) {
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
                debugState := debug_IDLE
              }
            }
          }
        }
      }
    }

    is(debug_POKE) {
      val id = UInt(wInNum) - pokecount
      io.host.in.ready := pokecount.orR
      when(io.host.in.fire()) {
        wInFFs(id) := io.host.in.bits 
        pokecount := pokecount - UInt(1)
      }.elsewhen(!io.host.in.ready) {
        debugState := debug_IDLE
      }
    }
    is(debug_PEEK) {
      val id = UInt(wOutNum) - peekcount
      io.host.out.bits  := wOutFFs(id)
      io.host.out.valid := peekcount.orR
      when(io.host.out.fire()) {
        peekcount := peekcount - UInt(1)
      }.elsewhen(!io.host.out.valid) {
        debugState := debug_IDLE
      }
    }

    if (dInNum > 0) {
      is(debug_POKED) {
        val id = UInt(dInNum) - pokedcount
        switch(pokedState) {
          is(poked_COUNT) {
            io.host.in.ready := pokedcount.orR
            when(io.host.in.fire()) {
              pokedlen := io.host.in.bits
              pokedcount := pokedcount - UInt(1)
              pokedState := poked_DATA
            }.elsewhen(!io.host.in.ready) {
              debugState := debug_IDLE
            }
          }
          is(poked_DATA) {
            dInEnqs(id).bits := io.host.in.bits
            dInEnqs(id).valid := io.host.in.valid && pokedlen.orR
            io.host.in.ready := dInEnqs(id).ready && pokedlen.orR
            when(io.host.in.fire()) {
              pokedlen := pokedlen - UInt(1)
            }.elsewhen(!io.host.in.ready) {
              pokedcount := pokedcount - UInt(1)
              pokedState := poked_COUNT
           }
          } 
        }
      }
    }

    if (dOutNum > 0) {
      // IO trace stage when any trace Q is full
      // Todo: this will be very slow...
      is(debug_PEEKD) {
        val id = UInt(dOutNum) - peekdcount
        switch(peekdState) {
          is(peekd_COUNT) {
            io.host.out.bits := dOutCnts(id)
            io.host.out.valid := peekdcount.orR
            when(io.host.out.fire()) {
              peekdState := peekd_DATA
            }.elsewhen(!io.host.out.valid) {
              debugState := Mux(stepcount.orR, debug_STEP, debug_IDLE)
            }
          }
          is(peekd_DATA) {
            io.host.out.bits := dOutDeqs(id).bits
            io.host.out.valid := dOutDeqs(id).valid
            dOutDeqs(id).ready := io.host.out.ready
            when(!io.host.out.valid) {
              peekdcount := peekdcount - UInt(1)
              peekdState := peekd_COUNT
            }
          } 
        }
      }
    }

    is(debug_TRACE) {
      switch(traceState) {
        is(trace_WCOUNT) {
          io.host.out.bits := wAddrTrace.io.count
          io.host.out.valid := Bool(true)
          when(io.host.out.fire()) {
            traceState := trace_WADDR
          }          
        }
        is(trace_WADDR) {
          io.host.out.bits := wAddrTrace.io.deq.bits.addr
          io.host.out.valid := wAddrTrace.io.deq.valid
          wAddrTrace.io.deq.ready := io.host.out.ready
          when(!io.host.out.valid) {
            traceState := trace_WDATA
          }
        }
        is(trace_WDATA) {
          io.host.out.bits := wDataTrace.io.deq.bits.data
          io.host.out.valid := wDataTrace.io.deq.valid
          wDataTrace.io.deq.ready := io.host.out.ready
          when(!io.host.out.valid) {
            traceState := trace_RCOUNT
          }
        }
        is(trace_RCOUNT) {
          io.host.out.bits := PopCount(rAddrValid)
          io.host.out.valid := Bool(true)
          when(io.host.out.fire()) {
            traceState := trace_RADDR
            raddrcount := UInt(tagNum)
          }
        }
        is(trace_RADDR) {
          val id = UInt(tagNum) - raddrcount
          io.host.out.bits := rAddrTrace(id).addr
          io.host.out.valid := rAddrValid(id)
          when(!raddrcount.orR) {
            traceState := trace_WCOUNT
            debugState := Mux(stepcount.orR, debug_STEP, debug_IDLE)    
          }.elsewhen(io.host.out.fire() || !io.host.out.valid) {
            raddrcount := raddrcount - UInt(1)
          }
        }
      }
    }

    is(debug_MEM) {
      switch(memState) {
        is(mem_REQ_CMD) {
          io.host.in.ready := addrcount.orR
          memReqCmdQ.io.enq.valid := !io.host.in.ready
          when(io.host.in.fire()) {
            memReqCmd.addr := (memReqCmd.addr << UInt(hostLen)) | io.host.in.bits
            addrcount := addrcount - UInt(1)
          }
          when(memReqCmdQ.io.enq.fire()) {
            memState := Mux(memReqCmd.rw, mem_REQ_DATA, mem_WAIT)
          }
        }
        is(mem_REQ_DATA) {
          io.host.in.ready := datacount.orR
          memReqDataQ.io.enq.valid := !io.host.in.ready
          when(io.host.in.fire()) {
            memReqData.data := (memReqData.data << UInt(hostLen)) | io.host.in.bits
            datacount := datacount - UInt(1)
          }
          when(memReqDataQ.io.enq.fire()) {
            memState := mem_REQ_CMD
            debugState := debug_IDLE
          }
        }
        is(mem_WAIT) {
          when(io.mem.resp.fire() && io.mem.resp.bits.tag === memReqCmd.tag) {
            memRespQ.io.enq.valid := Bool(false)
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
            memState := mem_REQ_CMD
            debugState := debug_IDLE
          }
        }
      }
    }
  }

  // add custom transforms for daisy chains
  DaisyBackend.addTransforms(daisyLen)
}
