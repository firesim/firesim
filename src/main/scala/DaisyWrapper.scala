package faee

import Chisel._

class DaisyWrapperIO[T <: Data](buswidth: Int = 64) extends Bundle {
  val hostIn = Decoupled(UInt(width=buswidth)).flip
  val hostOut = Decoupled(UInt(width=buswidth))
  val memIn = Decoupled(UInt(width=buswidth)).flip
  val memOut = Decoupled(UInt(width=buswidth))
}

object DaisyWrapper {
  def apply[T <: Module](c: =>T) = Module(new DaisyWrapper(c))
}

class DaisyWrapper[+T <: Module](c: =>T, 
  val buswidth: Int = 64, 
  val daisywidth: Int = 1, 
  val opwidth: Int = 6) extends Module {
  val io = new DaisyWrapperIO(buswidth)
  val target = Module(c)
  val inputs = for ((n, io) <- target.wires ; if io.dir == INPUT) yield io
  val outputs = for ((n, io) <- target.wires ; if io.dir == OUTPUT) yield io
  val inputBufs = Vec.fill(inputs.length) { Reg(UInt()) }
  val outputBufs = Vec.fill(outputs.length) { Reg(UInt()) }
  val stallPin = target.addPin(Bool(INPUT), "stall")
  val stateIn = target.addPin(Decoupled(UInt(width=daisywidth)).flip, "state_in")
  val stateOut = target.addPin(Decoupled(UInt(width=daisywidth)), "state_out")
  val sramIn = if (Driver.hasSRAM) target.addPin(Decoupled(UInt(width=daisywidth)).flip, "sram_in") else null
  val sramOut = if (Driver.hasSRAM) target.addPin(Decoupled(UInt(width=daisywidth)), "sram_out") else null
  val sramRestart = if (Driver.hasSRAM) target.addPin(Bool(INPUT), "restart") else null
  // val cntrIn = target.addPin(Decoupled(UInt(width=daisywidth)).flip, "cntr_in")
  // val cntrOut = target.addPin(Decoupled(UInt(width=daisywidth)), "cntr_out")

  // Step counters for simulation run or stall
  val s_IDLE :: s_STEP :: s_SNAP1 :: s_SNAP2 :: s_POKE :: s_PEEK :: Nil = Enum(UInt(), 6)
  val state = Reg(init=s_IDLE)
  val stepCounter = Reg(init=UInt(0))
  val pokeCounter = Reg(init=UInt(0))
  val peekCounter = Reg(init=UInt(0))
  val fire = stepCounter.orR
  val fireDelay = Reg(next=fire)
  stallPin := !fire

  // Debug APIs
  val STEP = UInt(0, opwidth)
  val POKE = UInt(1, opwidth)
  val PEEK = UInt(2, opwidth)

  // Registers for snapshotting
  val stateSnapBuf = Reg(UInt(width=buswidth))
  val stateSnapCount = Reg(UInt(width=log2Up(buswidth+1)))
  val sramSnapBuf = if (Driver.hasSRAM) Reg(UInt(width=buswidth)) else null
  val sramSnapCount = if (Driver.hasSRAM) Reg(UInt(width=log2Up(buswidth+1))) else null
  val sramRestartCount = if (Driver.hasSRAM) Reg(UInt(width=log2Up(Driver.sramMaxSize+1))) else null
  val sramReady = if (Driver.hasSRAM) Reg(init=Bool(false)) else null

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
  io.hostIn.ready := Bool(false)
  io.hostOut.valid := Bool(false)
  io.hostOut.bits := UInt(0)
  // Memory IO pins
  io.memIn.ready := Bool(false)
  io.memOut.valid := Bool(false)
  io.memOut.bits := UInt(0)
  // Daisy pins
  stateIn.bits := UInt(0)
  stateIn.valid := Bool(false)
  stateOut.ready := Bool(false)
  if (Driver.hasSRAM) {
    sramIn.bits := UInt(0)
    sramIn.valid := Bool(false)
    sramOut.ready := Bool(false)
    sramRestart := Bool(false)
  }

  val op = io.hostIn.bits(opwidth-1, 0)
  val stepNum = io.hostIn.bits(buswidth-1, opwidth)
  switch(state) {
    is(s_IDLE) {
      io.hostIn.ready := Bool(true)
      when(io.hostIn.fire()) {
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
        stateSnapCount := UInt(0)
        if (Driver.hasSRAM) {
          sramReady := Bool(false)
          sramSnapCount := UInt(0)
          sramRestartCount := UInt(Driver.sramMaxSize-1)
        }
      }
    }
    // Snapshotring inputs and registers
    is(s_SNAP1) {
      stateOut.ready := Bool(true)
      when(stateSnapCount >= UInt(buswidth)) { 
        when(io.memOut.ready) {
          io.memOut.bits := stateSnapBuf
          io.memOut.valid := Bool(true)
          stateSnapCount := stateSnapCount - UInt(buswidth-1)
        }
        when(!stateOut.valid) {
          if (Driver.hasSRAM) {
            sramRestart := Bool(true)
            state := s_SNAP2 
          } else { 
            state := s_IDLE
          }
        }
      }.otherwise {
        stateSnapCount := stateSnapCount + UInt(daisywidth)
      }
      stateSnapBuf := Cat(stateSnapBuf, stateOut.bits)
    }
    // Snapshotring SRAMs
    if (Driver.hasSRAM) {
      is(s_SNAP2) {
        sramOut.ready := Bool(true)
        when(!sramReady && sramOut.valid) {
          sramReady := Bool(true)
        }
        when(sramSnapCount >= UInt(buswidth-1)) {
          when(io.memOut.ready) {
            io.memOut.bits := sramSnapBuf
            io.memOut.valid := Bool(true)
            sramSnapCount := sramSnapCount - UInt(buswidth-1)
          }
          when(!sramOut.valid) {
            sramReady := Bool(false)
            when(sramRestartCount.orR) {
              sramRestartCount := sramRestartCount - UInt(1)
              sramRestart := Bool(true)
            }.otherwise {
              state := s_IDLE
            }
          }
        }.elsewhen(sramReady) {
          sramSnapCount := sramSnapCount + UInt(daisywidth)
        }
        sramSnapBuf := Cat(sramSnapBuf, sramOut.bits)
      } 
    }
    is(s_POKE) {
      val id = UInt(inputs.length) - pokeCounter
      val valid = io.hostIn.bits(0)
      val data  = io.hostIn.bits(buswidth-1, 1)
      when(pokeCounter.orR) {
        io.hostIn.ready := Bool(true)
        when (io.hostIn.valid) {
          when(valid) {
            inputBufs(id) := data
          }
          pokeCounter := pokeCounter - UInt(1)
        }
      }.otherwise {
        io.hostIn.ready := Bool(false)
        state := s_IDLE
      }
    }
    is(s_PEEK) {
      val id = UInt(outputs.length) - peekCounter
      when(peekCounter.orR) {
        when(io.hostOut.ready) {
          io.hostOut.valid := Bool(true)
          io.hostOut.bits := outputBufs(id)
          peekCounter := peekCounter - UInt(1)
        }.otherwise {
          io.hostOut.valid := Bool(false)
        }
      }.otherwise {
        io.hostOut.valid := Bool(false)
        state := s_IDLE
      }
    }
  }

  // add custom transforms for daisy chains
  DaisyBackend.addTransforms(daisywidth)
}
