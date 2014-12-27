package daisy

import Chisel._

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
class MemAddr extends HasMemAddr
class MemData extends HasMemData
class MemTag extends HasMemTag

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
  val targetMemPins = target.io match {
    case b: Bundle => b.elements find { case (n, wires) => {
      val hostMemIoNames   = io.mem.flatten.unzip._1
      val targetMemIoNames = wires.flatten.unzip._1.toSet
      hostMemIoNames forall (targetMemIoNames contains _)
    } }
    case _ => None
  }
  val targetMemIo = new MemIO
  targetMemPins match {
    case None =>
    case Some((n, io)) => {
      for ((n0, io0) <- io.flatten ; (n1, io1) <- targetMemIo.flatten ; if n0 == n1) {
        if (io0.dir == INPUT) {
          io0 := io1
        } else if (io0.dir == OUTPUT) {
          io1 := io0
        }
      }
    }
  }
  def isMemIo(name: String) = targetMemPins match {
    case None => false
    case Some((n, io)) => io.flatten.unzip._1 exists (n + "_" + _ == name)
  }
  val inputs = for ((n, io) <- target.wires ; if io.dir == INPUT && !isMemIo(n)) yield io 
  val outputs = for ((n, io) <- target.wires ; if io.dir == OUTPUT && !isMemIo(n)) yield io 

  // Machine states
  val (debug_IDLE :: debug_STEP :: debug_SNAP1 :: debug_SNAP2 :: 
       debug_POKE :: debug_PEEK :: debug_MEM :: debug_TRACE :: Nil) = Enum(UInt(), 8)
  val debugState = RegInit(debug_IDLE)
  val snap_IDLE :: snap_READ :: snap_SEND :: Nil = Enum(UInt(), 3)
  val snapState = RegInit(snap_IDLE)
  val mem_REQ_CMD :: mem_REQ_DATA :: mem_WAIT :: mem_RESP :: Nil = Enum(UInt(), 4)
  val memState = RegInit(mem_REQ_CMD)
  val trace_ADDR :: trace_DATA :: Nil = Enum(UInt(), 2)
  val traceState = RegInit(trace_ADDR)

  // For memory tracing
  val memTraceCount = Reg(UInt(width=log2Up(hostLen+1)))
  val memAddrTrace  = Module(new Queue(new MemAddr, 2))
  val memDataTrace  = Module(new Queue(new MemData, 2))

  // Step counters for simulation run or stall
  val stepCounter = RegInit(UInt(0))
  val pokeCounter = RegInit(UInt(0))
  val peekCounter = RegInit(UInt(0))
  val fire = stepCounter.orR && debugState === debug_STEP && 
             memAddrTrace.io.enq.ready && memDataTrace.io.enq.ready
  val fireDelay = RegNext(fire)

  // For snapshotting
  val isSnap      = RegInit(Bool(false))
  val snapMemAddr = Reg(UInt(width=addrLen))
  val snapBuffer  = Reg(UInt(width=hostLen+daisyLen))
  val snapCount   = Reg(UInt(width=log2Up(hostLen+1)))
  val snapReady   = Reg(Bool())
  val snapFinish  = Reg(Bool())
  val sramRestartCount = Reg(UInt(width=log2Up(Driver.sramMaxSize+1)))

  // For memory commands
  val memReqCmd = Reg(new MemReqCmd)
  val memReq    = Reg(new MemData)
  val memResp   = Reg(new MemData)
  val memTagCounter  = RegInit(UInt(0, tagLen))
  val memAddrCounter = Reg(UInt())
  val memDataCounter = Reg(UInt())
  val memReqCmdQueue = Module(new Queue(io.mem.req_cmd.bits.clone, 4))
  val memReqQueue    = Module(new Queue(io.mem.req_data.bits.clone, 4))
  val memRespQueue   = Module(new Queue(io.mem.resp.bits.clone, 2))

  // Connect target IOs with buffers
  val inputNum = (inputs foldLeft 0)((res, input) => res + (input.needWidth-1)/hostLen + 1)
  val outputNum = (outputs foldLeft 0)((res, output) => res + (output.needWidth-1)/hostLen + 1)
  val inputBufs = Vec.fill(inputNum) { Reg(UInt()) }
  val outputBufs = Vec.fill(outputNum) { Reg(UInt()) }
  var inputId = 0
  var outputId = 0
  for (input <- inputs) {
    // Resove width error
    input match {
      case _: Bool => inputBufs(inputId).init("", 1)
      case _ => 
    }
    val width = input.needWidth
    val n = (width-1) / hostLen + 1
    if (width <= hostLen) {
      input := Mux(fire, inputBufs(inputId), UInt(0))
      inputId += 1
    } else {
      val bufs = (0 until n) map { x => inputBufs(inputId + x) }
      input := Mux(fire, Cat(bufs), UInt(0))
      inputId += n
    }
  }
  for (output <- outputs) {
    output match {
      case _: Bool => outputBufs(outputId).init("", 1)
      case _ => 
    }
    when (fireDelay) {
      val width = output.needWidth
      val n = (width-1) / hostLen + 1
      for (i <- 0 until n) {
        val low = i * hostLen
        val high = math.min(hostLen-1+low, width-1)
        outputBufs(outputId) := output(high, low)
        outputId += 1
      }
    }
  }

  // Host pins
  io.host.in.ready  := Bool(false)
  io.host.out.valid := Bool(false)
  io.host.out.bits  := UInt(0)

  // Memory Requests
  // Todo: io.mem.req_cmd <> memReqCmdQueue.io.deq
  io.mem.req_cmd.bits := memReqCmdQueue.io.deq.bits
  io.mem.req_cmd.valid := memReqCmdQueue.io.deq.valid
  memReqCmdQueue.io.deq.ready := io.mem.req_cmd.ready
  // Todo: io.mem.req_data <> memReqQueue.io.deq
  io.mem.req_data.bits := memReqQueue.io.deq.bits
  io.mem.req_data.valid := memReqQueue.io.deq.valid
  memReqQueue.io.deq.ready := io.mem.req_data.ready
 
  // Memory response
  // Todo: memRespQueue.io.enq <> io.mem.resp
  memRespQueue.io.enq.bits  := io.mem.resp.bits
  memRespQueue.io.enq.valid := io.mem.resp.valid
  io.mem.resp.ready         := memRespQueue.io.enq.ready

  if (targetMemPins != None) {
    memReqCmdQueue.io.enq.bits  := Mux(fire, targetMemIo.req_cmd.bits, memReqCmd)
    memReqCmdQueue.io.enq.valid := fire && targetMemIo.req_cmd.valid
    targetMemIo.req_cmd.ready   := memReqCmdQueue.io.enq.ready && memAddrTrace.io.enq.ready
    memReqQueue.io.enq.bits     := Mux(fire, targetMemIo.req_data.bits, memReq)
    memReqQueue.io.enq.valid    := fire && targetMemIo.req_data.valid
    targetMemIo.req_data.ready  := memReqQueue.io.enq.ready && memDataTrace.io.enq.ready
    targetMemIo.resp.bits       := memRespQueue.io.deq.bits
    targetMemIo.resp.valid      := memRespQueue.io.deq.valid
    memRespQueue.io.deq.ready   := fire && targetMemIo.resp.ready
    // Memory traceing
    memAddrTrace.io.enq.bits.addr := targetMemIo.req_cmd.bits.addr
    memAddrTrace.io.enq.valid     := fire && targetMemIo.req_cmd.valid && targetMemIo.req_cmd.bits.rw 
    memDataTrace.io.enq.bits.data := targetMemIo.req_data.bits.data
    memDataTrace.io.enq.valid     := fire && targetMemIo.req_data.valid
  } else {
    memReqCmdQueue.io.enq.bits    := memReqCmd
    memReqCmdQueue.io.enq.valid   := Bool(false)
    memReqQueue.io.enq.bits       := memReq
    memReqQueue.io.enq.valid      := Bool(false)
    memRespQueue.io.deq.ready     := Bool(true)

    memAddrTrace.io.enq.bits.addr := Bits(0)
    memAddrTrace.io.enq.valid     := Bool(false)
    memDataTrace.io.enq.bits.data := Bits(0)
    memDataTrace.io.enq.valid     := Bool(false)
  }
  memAddrTrace.io.deq.ready := Bool(false)
  memDataTrace.io.deq.ready := Bool(false)
 
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

  switch(debugState) {
    is(debug_IDLE) {
      io.host.in.ready := Bool(true)
      when(io.host.in.fire()) {
        val cmd = io.host.in.bits(cmdLen-1, 0)
        when(cmd === STEP) {
          isSnap := io.host.in.bits(cmdLen)
          stepCounter := io.host.in.bits(hostLen-1, cmdLen+1)
          debugState := debug_STEP
        }.elsewhen(cmd === POKE) {
          pokeCounter := UInt(inputNum)
          debugState := debug_POKE
        }.elsewhen(cmd === PEEK) {
          peekCounter := UInt(outputNum)
          debugState := debug_PEEK
        }.elsewhen(cmd === MEM) {
          memReqCmd.rw   := io.host.in.bits(cmdLen) 
          memReqCmd.tag  := memTagCounter
          memAddrCounter := UInt((addrLen-1)/hostLen + 1)
          memDataCounter := UInt((memLen-1)/hostLen + 1)
          debugState     := debug_MEM
        }
      }
    }

    is(debug_STEP) {
      when(fire) {
        stepCounter := stepCounter - UInt(1)
      }.elsewhen(io.host.out.ready) {
        when(stepCounter.orR) {
          // Full of mem trace queues
          io.host.out.bits  := memDataTrace.io.count // output # elements
          io.host.out.valid := Bool(true)
          memTraceCount     := UInt(0)
          debugState        := debug_TRACE 
        }.elsewhen(RegNext(!stepCounter.orR)) {
          when(memDataTrace.io.deq.valid) {
            // Finish, but has mem writes
            io.host.out.bits  := memDataTrace.io.count // output # elements
            io.host.out.valid := Bool(true)
            memTraceCount     := UInt(0)
            debugState        := debug_TRACE
          }.otherwise {
            when(isSnap) {
              debugState := debug_SNAP1
              isSnap := Bool(false)
            }.otherwise {
              debugState := debug_IDLE
            }
            snapCount := UInt(0)
            snapReady := Bool(false)
            if (Driver.hasSRAM) {
              sramRestartCount := UInt(Driver.sramMaxSize-1)
            }
            io.host.out.bits  := UInt(0) // output 0
            io.host.out.valid := Bool(true) 
          }
        }
      }
    }
    // Memory tracing
    is(debug_TRACE) {
      switch(traceState) {
        is(trace_ADDR) {
          io.host.out.bits  := memAddrTrace.io.deq.bits.addr >> memTraceCount
          io.host.out.valid := memTraceCount < UInt(addrLen)
          when(io.host.out.fire()) {
            memTraceCount := memTraceCount + UInt(hostLen)
          }.elsewhen(!io.host.out.valid) {
            memTraceCount := UInt(0)
            memAddrTrace.io.deq.ready := Bool(true)
            traceState := trace_DATA
          }
        }
        is(trace_DATA) {
          io.host.out.bits  := memDataTrace.io.deq.bits.data >> memTraceCount
          io.host.out.valid := memTraceCount < UInt(addrLen)
          when(io.host.out.fire()) {
            memTraceCount := memTraceCount + UInt(hostLen)
          }.elsewhen(!io.host.out.valid) {
            memTraceCount := UInt(0)
            memDataTrace.io.deq.ready := Bool(true)
            traceState := trace_ADDR 
            debugState := Mux(memAddrTrace.io.deq.valid, debug_TRACE, debug_STEP)
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
          when(snapCount < UInt(hostLen)) {
            daisy.regs.out.ready := Bool(true)
            snapBuffer := Cat(snapBuffer, daisy.regs.out.bits)
            snapCount := snapCount + UInt(daisyLen)
          }.otherwise {
            snapCount := snapCount - UInt(hostLen)
            snapState := snap_SEND
          }
        }
        is(snap_SEND) {
          when(io.host.out.ready) {
            io.host.out.bits  := snapBuffer >> snapCount
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
            when(snapCount < UInt(hostLen)) {
              daisy.sram.out.ready := Bool(true)
              snapBuffer := Cat(snapBuffer, daisy.sram.out.bits)
              snapCount := snapCount + UInt(daisyLen)
            }.otherwise {
              snapCount := snapCount - UInt(hostLen)
              snapState := snap_SEND
            }
          }
          is(snap_SEND) {
            when(io.host.out.ready) {
              io.host.out.bits  := snapBuffer >> snapCount
              io.host.out.valid := Bool(true)
              when (daisy.sram.out.valid) {
                snapState := snap_READ
              }.elsewhen (sramRestartCount.orR) {
                sramRestartCount := sramRestartCount - UInt(1)
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
      val id = UInt(inputNum) - pokeCounter
      io.host.in.ready := pokeCounter.orR
      when(io.host.in.fire()) {
        inputBufs(id) := io.host.in.bits 
        pokeCounter := pokeCounter - UInt(1)
      }.elsewhen(!io.host.in.ready) {
        debugState := debug_IDLE
      }
    }
    is(debug_PEEK) {
      val id = UInt(outputNum) - peekCounter
      io.host.out.bits  := outputBufs(id)
      io.host.out.valid := peekCounter.orR
      when(io.host.out.fire()) {
        peekCounter := peekCounter - UInt(1)
      }.elsewhen(!io.host.out.valid) {
        debugState := debug_IDLE
      }
    }

    is(debug_MEM) {
      switch(memState) {
        is(mem_REQ_CMD) {
          io.host.in.ready := memAddrCounter.orR
          memReqCmdQueue.io.enq.valid := !io.host.in.ready
          when(io.host.in.fire()) {
            memReqCmd.addr := (memReqCmd.addr << UInt(hostLen)) | io.host.in.bits
            memAddrCounter := memAddrCounter - UInt(1)
          }
          when(memReqCmdQueue.io.enq.fire()) {
            memState := Mux(memReqCmd.rw, mem_REQ_DATA, mem_WAIT)
          }
        }
        is(mem_REQ_DATA) {
          io.host.in.ready := memDataCounter.orR
          memReqQueue.io.enq.valid := !io.host.in.ready
          when(io.host.in.fire()) {
            memReq.data    := (memReq.data << UInt(hostLen)) | io.host.in.bits
            memDataCounter := memDataCounter - UInt(1)
          }
          when(memReqQueue.io.enq.fire()) {
            memState   := mem_REQ_CMD
            debugState := debug_IDLE
          }
        }
        is(mem_WAIT) {
          when(io.mem.resp.fire() && io.mem.resp.bits.tag === memReqCmd.tag) {
            memRespQueue.io.enq.valid := Bool(false)
            memResp.data := io.mem.resp.bits.data
            memState     := mem_RESP
          }
        }
        is(mem_RESP) {
          io.host.out.valid := memDataCounter.orR
          when(io.host.out.fire()) {
            io.host.out.bits := memResp.data(hostLen-1, 0)
            memResp.data     := memResp.data >> UInt(hostLen)
            memDataCounter   := memDataCounter - UInt(1)
          }.elsewhen(!io.host.out.valid) {
            memState   := mem_REQ_CMD
            debugState := debug_IDLE
          }
        }
      }
    }
  }

  // add custom transforms for daisy chains
  DaisyBackend.addTransforms(daisyLen)
}
