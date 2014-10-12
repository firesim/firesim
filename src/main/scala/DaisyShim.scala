package DebugMachine

import Chisel._

case object BusWidth extends Field[Int]
case object AddrWidth extends Field[Int]
case object TagWidth extends Field[Int]
case object DaisyWidth extends Field[Int]
case object OpWidth extends Field[Int]

object DaisyShim {
  val opwidth = 6
  val daisy_parameters = Parameters.empty alter (
    (key, site, here, up) => key match { 
      case BusWidth => 64
      case AddrWidth => 32
      case TagWidth => 5
      case DaisyWidth => 32
      case OpWidth => opwidth
    })
  def apply[T <: Module](c: =>T) = Module(new DaisyShim(c))(daisy_parameters)
}

abstract trait DaisyShimParams extends UsesParameters {
  val buswidth = params(BusWidth) 
  val addrwidth = params(AddrWidth)
  val tagwidth = params(TagWidth)
  val daisywidth = params(DaisyWidth)
}

abstract trait DebugCommands extends UsesParameters {
  val opwidth = params(OpWidth)
  val STEP = UInt(0, opwidth)
  val POKE = UInt(1, opwidth)
  val PEEK = UInt(2, opwidth)
}

abstract trait DaisyBundle extends Bundle with DaisyShimParams

class HostIO extends DaisyBundle {
  val in = Decoupled(UInt(width=buswidth)).flip
  val out = Decoupled(UInt(width=buswidth))
}

trait HasMemData extends DaisyBundle {
  val data = UInt(width=buswidth)
}

trait HasMemAddr extends DaisyBundle {
  val addr = UInt(width=addrwidth)
}

trait HasMemTag extends DaisyBundle {
  val tag = UInt(width=tagwidth)
}

class MemReqCmd extends HasMemData with HasMemTag
class MemResp extends HasMemData with HasMemTag
class MemData extends HasMemData

class MemIO extends DaisyBundle {
  val reqCmd = Decoupled(new MemReqCmd)
  val reqData = Decoupled(new MemData)
  val resp = Decoupled(new MemResp).flip
}

class DaisyShimIO extends Bundle {
  val host = new HostIO
  val mem = new MemIO
}

class DaisyShim[+T <: Module](c: =>T) extends Module with DaisyShimParams with DebugCommands {
  val io = new DaisyShimIO
  val target = Module(c)
  val inputs = for ((n, io) <- target.wires ; if io.dir == INPUT) yield io
  val outputs = for ((n, io) <- target.wires ; if io.dir == OUTPUT) yield io
  val inputBufs = Vec.fill(inputs.length) { Reg(UInt()) }
  val outputBufs = Vec.fill(outputs.length) { Reg(UInt()) }

  // Step counters for simulation run or stall
  val s_IDLE :: s_STEP :: s_SNAP1 :: s_SNAP2 :: s_POKE :: s_PEEK :: Nil = Enum(UInt(), 6)
  val state = Reg(init=s_IDLE)
  val stepCounter = Reg(init=UInt(0))
  val pokeCounter = Reg(init=UInt(0))
  val peekCounter = Reg(init=UInt(0))
  val fire = stepCounter.orR
  val fireDelay = Reg(next=fire)

  // Counters for snapshotting
  val snapBuffer = Reg(UInt(width=buswidth+daisywidth))
  val snapCount = Reg(UInt(width=log2Up(buswidth+1)))
  val snapReady = Reg(Bool())
  val snapFinish = Reg(Bool())
  val sramRestartCount = if (Driver.hasSRAM) Reg(UInt(width=log2Up(Driver.sramMaxSize+1))) else null

  // Connect target IOs with buffers
  for ((input, i) <- inputs.zipWithIndex) {
    // Resove width error
    input match {
      case _: Bool => inputBufs(i).init("", 1)
      case _ => 
    }
    input := inputBufs(i)
  }
  for ((output, i) <- outputs.zipWithIndex) {
    // Resove width error
    output match {
      case _: Bool => outputBufs(i).init("", 1)
      case _ => 
    }
    when (fireDelay) {
      outputBufs(i) := output
    }
  }

  // HostIO pins
  io.host.in.ready := Bool(false)
  io.host.out.valid := Bool(false)
  io.host.out.bits := UInt(0)
  // Memory IO pins
  io.mem.reqData.valid := Bool(false)
  io.mem.reqData.bits.data := UInt(0)
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

  val op = io.host.in.bits(opwidth-1, 0)
  val stepNum = io.host.in.bits(buswidth-1, opwidth)
  switch(state) {
    is(s_IDLE) {
      io.host.in.ready := Bool(true)
      when(io.host.in.fire()) {
        when(op === STEP) {
          stepCounter := stepNum
          state := s_STEP
        }.elsewhen(op === POKE) {
          pokeCounter := UInt(inputs.length)
          state := s_POKE
        }.elsewhen(op === PEEK) {
          peekCounter := UInt(outputs.length)
          state := s_PEEK
        }
      }
    }
    is(s_STEP) {
      when(fire) {
        stepCounter := stepCounter - UInt(1)
      }.elsewhen(!fireDelay) {
        state := s_SNAP1
        snapCount := UInt(0)
        snapReady := Bool(false)
        if (Driver.hasSRAM) {
          sramRestartCount := UInt(Driver.sramMaxSize-1)
        }
      }
    }
    // Snapshotring inputs and registers
    is(s_SNAP1) {
      daisy.state.out.ready := snapReady
      when(!snapReady && daisy.state.out.valid) {
        snapReady := Bool(true)
      }
      snapFinish := !daisy.state.out.valid
      when(snapCount >= UInt(buswidth)) { 
        when(io.mem.reqData.ready) {
          io.mem.reqData.bits.data := snapBuffer >> (snapCount - UInt(buswidth))
          io.mem.reqData.valid := Bool(true)
          snapCount := snapCount - UInt(buswidth-daisywidth)
        }
        when(snapFinish || !daisy.state.out.valid && snapCount === UInt(buswidth)){ 
          snapCount := UInt(0)
          snapReady := Bool(false)
          snapFinish := Bool(false)
          if (Driver.hasSRAM) {
            daisy.sram.restart := Bool(true)
            state := s_SNAP2 
          } else { 
            state := s_IDLE
          }
        }
      }.elsewhen(snapReady) {
        snapCount := snapCount + UInt(daisywidth)
      }
      snapBuffer := Cat(snapBuffer, daisy.state.out.bits)
    }
    // Snapshotring SRAMs
    if (Driver.hasSRAM) {
      is(s_SNAP2) {
        daisy.sram.out.ready := snapReady
        when(!snapReady && daisy.sram.out.valid) {
          snapReady := Bool(true)
        }
        snapFinish := !daisy.state.out.valid
        when(snapCount >= UInt(buswidth)) {
          when(io.mem.reqData.ready) {
            io.mem.reqData.bits.data := snapBuffer
            io.mem.reqData.valid := Bool(true)
            snapCount := snapCount - UInt(buswidth-daisywidth)
          }
          when(snapFinish || !daisy.sram.out.valid && snapCount === UInt(buswidth)) { 
            snapCount := UInt(0)
            snapReady := Bool(false)
            snapFinish := Bool(false)
            when(sramRestartCount.orR) {
              sramRestartCount := sramRestartCount - UInt(1)
              daisy.sram.restart := Bool(true)
            }.otherwise {
              state := s_IDLE
            }
          }
        }.elsewhen(snapReady) {
          snapCount := snapCount + UInt(daisywidth)
        }
        snapBuffer := Cat(snapBuffer, daisy.sram.out.bits)
      } 
    }
    is(s_POKE) {
      val id = UInt(inputs.length) - pokeCounter
      val valid = io.host.in.bits(0)
      val data  = io.host.in.bits(buswidth-1, 1)
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
        state := s_IDLE
      }
    }
    is(s_PEEK) {
      val id = UInt(outputs.length) - peekCounter
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
        state := s_IDLE
      }
    }
  }

  // add custom transforms for daisy chains
  DaisyBackend.addTransforms(daisywidth)
}
