package Daisy

import Chisel._

case object HostWidth extends Field[Int]
case object MemWidth extends Field[Int]
case object AddrWidth extends Field[Int]
case object TagWidth extends Field[Int]
case object DaisyWidth extends Field[Int]
case object OpWidth extends Field[Int]

object DaisyShim {
  val daisy_parameters = Parameters.empty alter (
    (key, site, here, up) => key match {
      case HostWidth => 32
      case MemWidth => 64
      case AddrWidth => 32
      case TagWidth => 5
      case DaisyWidth => 32
      case OpWidth => 6
    })
  def apply[T <: Module](c: =>T) = Module(new DaisyShim(c))(daisy_parameters)
}

abstract trait DaisyShimParams extends UsesParameters {
  val hostwidth = params(HostWidth)
  val memwidth = params(MemWidth) 
  val addrwidth = params(AddrWidth)
  val tagwidth = params(TagWidth)
  val daisywidth = params(DaisyWidth)
}

abstract trait DebugCommands extends UsesParameters {
  val opwidth = params(OpWidth)
  val STEP = UInt(0, opwidth)
  val POKE = UInt(1, opwidth)
  val PEEK = UInt(2, opwidth)
  val SNAP = UInt(3, opwidth)
}

abstract trait DaisyBundle extends Bundle with DaisyShimParams

class HostIO extends DaisyBundle {
  val in = Decoupled(UInt(width=hostwidth)).flip
  val out = Decoupled(UInt(width=hostwidth))
}

trait HasMemData extends DaisyBundle {
  val data = UInt(width=memwidth)
}

trait HasMemAddr extends DaisyBundle {
  val addr = UInt(width=addrwidth)
}

trait HasMemTag extends DaisyBundle {
  val tag = UInt(width=tagwidth)
}

class MemReqCmd extends HasMemAddr with HasMemTag {
  val rw = Bool()
}
class MemResp extends HasMemData with HasMemTag
class MemData extends HasMemData

class MemIO extends DaisyBundle {
  val req_cmd = Decoupled(new MemReqCmd)
  val req_data = Decoupled(new MemData)
  val resp = Decoupled(new MemResp).flip
}

class DaisyShimIO extends Bundle {
  val host = new HostIO
  val mem = new MemIO // Not used now...
}

class DaisyShim[+T <: Module](c: =>T) extends Module with DaisyShimParams with DebugCommands {
  val io = new DaisyShimIO
  val target = Module(c)
  val inputs = for ((n, io) <- target.wires ; if io.dir == INPUT) yield io
  val outputs = for ((n, io) <- target.wires ; if io.dir == OUTPUT) yield io

  // Step counters for simulation run or stall
  val debug_IDLE :: debug_STEP :: debug_SNAP1 :: debug_SNAP2 :: debug_POKE :: debug_PEEK :: Nil = Enum(UInt(), 6)
  val debugState = Reg(init=debug_IDLE)
  val snap_IDLE :: snap_READ :: snap_SEND :: Nil = Enum(UInt(), 3)
  val snapState = Reg(init=snap_IDLE)

  val stepCounter = Reg(init=UInt(0))
  val pokeCounter = Reg(init=UInt(0))
  val peekCounter = Reg(init=UInt(0))
  val fire = stepCounter.orR
  val fireDelay = Reg(next=fire)

  // For snapshotting
  val isSnap = Reg(init=Bool(false))
  val snapMemAddr = Reg(UInt(width=addrwidth))
  val snapBuffer = Reg(UInt(width=hostwidth+daisywidth))
  val snapCount = Reg(UInt(width=log2Up(hostwidth+1)))
  val snapReady = Reg(Bool())
  val snapFinish = Reg(Bool())
  val sramRestartCount = Reg(UInt(width=log2Up(Driver.sramMaxSize+1)))

  // Connect target IOs with buffers
  val inputNum = (inputs foldLeft 0)((res, input) => res + (input.needWidth-1)/(hostwidth-1) + 1)
  val outputNum = (outputs foldLeft 0)((res, output) => res + (output.needWidth-1)/hostwidth + 1)
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
    val n = (width-1) / (hostwidth-1) + 1
    if (width <= hostwidth-1) {
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
      val n = (width-1) / hostwidth + 1
      for (i <- 0 until n) {
        val low = i * hostwidth
        val high = math.min(hostwidth-1+low, width-1)
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
  io.mem.req_cmd.valid := Bool(false)
  io.mem.req_cmd.bits.rw := Bool(false)
  io.mem.req_cmd.bits.tag := UInt(0)
  io.mem.req_cmd.bits.addr := UInt(0)
  io.mem.req_data.valid := Bool(false)
  io.mem.req_data.bits.data := UInt(0)
  // Daisy pins
  val daisy = addDaisyPins(target, daisywidth)
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
        val op = io.host.in.bits(opwidth-1, 0)
        when(op === STEP) {
          val stepNum = io.host.in.bits(hostwidth-1, opwidth)
          stepCounter := stepNum
          debugState := debug_STEP
        }.elsewhen(op === POKE) {
          pokeCounter := UInt(inputNum)
          debugState := debug_POKE
        }.elsewhen(op === PEEK) {
          peekCounter := UInt(outputNum)
          debugState := debug_PEEK
        }.elsewhen(op === SNAP) {
          isSnap := Bool(true)
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
          when(daisy.state.out.valid) {
            snapState := snap_READ
          }
        }
        is(snap_READ) {
          when(snapCount < UInt(hostwidth)) {
            daisy.state.out.ready := Bool(true)
            snapBuffer := Cat(snapBuffer, daisy.state.out.bits)
            snapCount := snapCount + UInt(daisywidth)
          }.otherwise {
            snapCount := snapCount - UInt(hostwidth)
            snapState := snap_SEND
          }
        }
        is(snap_SEND) {
          when(io.host.out.ready) {
            io.host.out.valid := Bool(true)
            io.host.out.bits := snapBuffer >> snapCount
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
            when(daisy.sram.out.valid) {
              snapState := snap_READ
            }
          }
          is(snap_READ) {
            when(snapCount < UInt(hostwidth)) {
              daisy.sram.out.ready := Bool(true)
              snapBuffer := Cat(snapBuffer, daisy.sram.out.bits)
              snapCount := snapCount + UInt(daisywidth)
            }.otherwise {
              snapCount := snapCount - UInt(hostwidth)
              snapState := snap_SEND
            }
          }
          is(snap_SEND) {
            when(io.host.out.ready) {
              io.host.out.valid := Bool(true)
              io.host.out.bits := snapBuffer >> snapCount
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
      val data  = io.host.in.bits(hostwidth-1, 1)
      when(pokeCounter.orR) {
        io.host.in.ready := Bool(true)
        when (io.host.in.valid) {
          when(valid) {
            inputBufs(id) := data
          }
          pokeCounter := pokeCounter - UInt(1)
        }
      }.otherwise {
        io.host.in.ready := Bool(false)
        debugState := debug_IDLE
      }
    }
    is(debug_PEEK) {
      val id = UInt(outputNum) - peekCounter
      when(peekCounter.orR) {
        when(io.host.out.ready) {
          io.host.out.valid := Bool(true)
          io.host.out.bits := outputBufs(id)
          peekCounter := peekCounter - UInt(1)
        }.otherwise {
          io.host.out.valid := Bool(false)
        }
      }.otherwise {
        io.host.out.valid := Bool(false)
        debugState := debug_IDLE
      }
    }
  }

  // add custom transforms for daisy chains
  DaisyBackend.addTransforms(daisywidth)
}
