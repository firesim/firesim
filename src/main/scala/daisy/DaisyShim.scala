package Daisy

import Chisel._

case object HostLen extends Field[Int]
case object AddrLen extends Field[Int]
case object TagLen extends Field[Int]
case object MemLen extends Field[Int]
case object DaisyLen extends Field[Int]
case object CmdLen extends Field[Int]

object DaisyShim {
  val daisy_parameters = Parameters.empty alter (
    (key, site, here, up) => key match {
      case HostLen => 32
      case AddrLen => 32
      case TagLen => 5
      case MemLen => 32 
      case DaisyLen => 32
      case CmdLen => 6
    })
  def apply[T <: Module](c: =>T) = Module(new DaisyShim(c))(daisy_parameters)
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

class MemIO extends Bundle {
  val req_cmd = Decoupled(new MemReqCmd)
  val req_data = Decoupled(new MemData)
  val resp = Decoupled(new MemResp).flip
}

class DaisyShimIO extends Bundle {
  val host = new HostIO
  val mem = new MemIO 
}

abstract trait DaisyShimParams extends UsesParameters {
  val hostLen = params(HostLen)
  val addrLen = params(AddrLen)
  val tagLen = params(TagLen)
  val memLen = params(MemLen) 
  val daisyLen = params(DaisyLen)
}

abstract trait DebugCommands extends UsesParameters {
  val cmdLen = params(CmdLen)
  val STEP = UInt(0, cmdLen)
  val POKE = UInt(1, cmdLen)
  val PEEK = UInt(2, cmdLen)
  val SNAP = UInt(3, cmdLen)
  val MEM  = UInt(4, cmdLen)
}

class DaisyShim[+T <: Module](c: =>T) extends Module with DaisyShimParams with DebugCommands {
  val io = new DaisyShimIO
  val target = Module(c)
  val inputs = for ((n, io) <- target.wires ; if io.dir == INPUT) yield io
  val outputs = for ((n, io) <- target.wires ; if io.dir == OUTPUT) yield io

  // Step counters for simulation run or stall
  val (debug_IDLE :: debug_STEP :: debug_SNAP1 :: debug_SNAP2 :: 
       debug_POKE :: debug_PEEK :: debug_MEM :: Nil) = Enum(UInt(), 7)
  val debugState = RegInit(debug_IDLE)
  val snap_IDLE :: snap_READ :: snap_SEND :: Nil = Enum(UInt(), 3)
  val snapState = RegInit(snap_IDLE)
  val mem_REQ_CMD :: mem_REQ_DATA :: mem_WAIT :: mem_RESP :: Nil = Enum(UInt(), 4)
  val memState = RegInit(mem_REQ_CMD)

  val stepCounter = RegInit(UInt(0))
  val pokeCounter = RegInit(UInt(0))
  val peekCounter = RegInit(UInt(0))
  val fire = stepCounter.orR
  val fireDelay = RegNext(fire)

  // For snapshotting
  val isSnap = RegInit(Bool(false))
  val snapMemAddr = Reg(UInt(width=addrLen))
  val snapBuffer = Reg(UInt(width=hostLen+daisyLen))
  val snapCount = Reg(UInt(width=log2Up(hostLen+1)))
  val snapReady = Reg(Bool())
  val snapFinish = Reg(Bool())
  val sramRestartCount = Reg(UInt(width=log2Up(Driver.sramMaxSize+1)))

  // For memory cmd
  val memRW   = Reg(Bool())
  val memTag  = RegInit(UInt(0, tagLen))
  val memAddr = Reg(UInt(width=addrLen))
  val memData = Reg(UInt(width=memLen))
  val memAddrCounter = Reg(UInt())
  val memDataCounter = Reg(UInt())
  val memReqCmdQueue = Module(new Queue(io.mem.req_cmd.bits.clone, 2))
  val memReqDataQueue = Module(new Queue(io.mem.req_data.bits.clone, 2))

  // Connect target IOs with buffers
  val inputNum = (inputs foldLeft 0)((res, input) => res + (input.needWidth-1)/(hostLen-1) + 1)
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
    val n = (width-1) / (hostLen-1) + 1
    if (width <= hostLen-1) {
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

  // HostIO pins
  io.host.in.ready := Bool(false)
  io.host.out.valid := Bool(false)
  io.host.out.bits := UInt(0)
  // Memory IO pins
  io.mem.req_cmd <> memReqCmdQueue.io.deq
  io.mem.req_data <> memReqDataQueue.io.deq
  memReqCmdQueue.io.enq.bits.rw   := memRW
  memReqCmdQueue.io.enq.bits.tag  := memTag
  memReqCmdQueue.io.enq.bits.addr := memAddr
  memReqCmdQueue.io.enq.valid := Bool(false)
  memReqDataQueue.io.enq.bits.data := memData
  memReqDataQueue.io.enq.valid := Bool(false)
  io.mem.resp.ready := Bool(false)
  // Daisy pins
  val daisy = addDaisyPins(target, daisyLen)
  daisy.stall := !fire
  daisy.state.in.bits := UInt(0)
  daisy.state.in.valid := Bool(false)
  daisy.state.out.ready := Bool(false)
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
          val stepNum = io.host.in.bits(hostLen-1, cmdLen)
          stepCounter := stepNum
          debugState := debug_STEP
        }.elsewhen(cmd === POKE) {
          pokeCounter := UInt(inputNum)
          debugState := debug_POKE
        }.elsewhen(cmd === PEEK) {
          peekCounter := UInt(outputNum)
          debugState := debug_PEEK
        }.elsewhen(cmd === SNAP) {
          isSnap := Bool(true)
        }.elsewhen(cmd === MEM) {
          memRW := io.host.in.bits(cmdLen)
          memAddr := UInt(0)
          memData := UInt(0)
          memAddrCounter := UInt((addrLen-1)/hostLen + 1)
          memDataCounter := UInt((memLen-1)/hostLen + 1)
          debugState := debug_MEM
        }
      }
    }
    is(debug_STEP) {
      when(fire) {
        stepCounter := stepCounter - UInt(1)
      }.elsewhen(!fireDelay) {
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
      }
    }
    // Snapshoting inputs and registers
    is(debug_SNAP1) {
      switch(snapState) {
        is(snap_IDLE) {
          snapState := Mux(daisy.state.out.valid, snap_READ, snap_IDLE)
        }
        is(snap_READ) {
          when(snapCount < UInt(hostLen)) {
            daisy.state.out.ready := Bool(true)
            snapBuffer := Cat(snapBuffer, daisy.state.out.bits)
            snapCount := snapCount + UInt(daisyLen)
          }.otherwise {
            snapCount := snapCount - UInt(hostLen)
            snapState := snap_SEND
          }
        }
        is(snap_SEND) {
          io.host.out.bits  := snapBuffer >> snapCount
          io.host.out.valid := Bool(true)
          when(io.host.out.fire()) {
            when (daisy.state.out.valid) {
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
            io.host.out.bits  := snapBuffer >> snapCount
            io.host.out.valid := Bool(true)
            when(io.host.out.fire()) {
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
      val valid = io.host.in.bits(0)
      val data  = io.host.in.bits(hostLen-1, 1)
      io.host.in.ready := pokeCounter.orR
      when(io.host.in.fire()) {
        inputBufs(id) := Mux(valid, data, inputBufs(id))
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
            memAddr := (memAddr << UInt(hostLen)) | io.host.in.bits
            memAddrCounter := memAddrCounter - UInt(1)
          }
          when(memReqCmdQueue.io.enq.fire()) {
            memState := Mux(memRW, mem_REQ_DATA, mem_WAIT)
          }
        }
        is(mem_REQ_DATA) {
          io.host.in.ready := memDataCounter.orR
          memReqDataQueue.io.enq.valid := !io.host.in.ready
          when(io.host.in.fire()) {
            memData := (memData << UInt(hostLen)) | io.host.in.bits
            memDataCounter := memDataCounter - UInt(1)
          }
          when(memReqDataQueue.io.enq.fire()) {
            memState := mem_REQ_CMD
            debugState := debug_IDLE
          }
        }
        is(mem_WAIT) {
          io.mem.resp.ready := Bool(true)
          when(io.mem.resp.fire() && io.mem.resp.bits.tag === memTag /* Should match! */) {
            memData  := io.mem.resp.bits.data
            memTag   := memTag + UInt(1)
            memState := mem_RESP
          }
        }
        is(mem_RESP) {
          io.host.out.valid := memDataCounter.orR
          when(io.host.out.fire()) {
            io.host.out.bits := memData(hostLen-1, 0)
            memData := memData >> UInt(hostLen)
            memDataCounter := memDataCounter - UInt(1)
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
