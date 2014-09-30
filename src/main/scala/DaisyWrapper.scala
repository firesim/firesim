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
  val firePin = target.addPin(Bool(INPUT), "fire")
  val stateIn = target.addPin(Decoupled(UInt(width=daisywidth)).flip, "state_in")
  val stateOut = target.addPin(Decoupled(UInt(width=daisywidth)), "state_out")
  val sramIn = target.addPin(Decoupled(UInt(width=daisywidth)).flip, "sram_in")
  val sramOut = target.addPin(Decoupled(UInt(width=daisywidth)), "sram_out")
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
  firePin := fire

  // Debug APIs
  val STEP = UInt(0, opwidth)
  val POKE = UInt(1, opwidth)
  val PEEK = UInt(2, opwidth)

  // Registers for snapshotting
  val stateSnapCount = Reg(UInt(width=log2Up(buswidth+1)))
  val sramSnapCount = Reg(UInt(width=log2Up(buswidth+1)))
  val stateSnapBuf = Reg(UInt(width=buswidth))
  val sramSnapBuf = Reg(UInt(width=buswidth))

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
  sramIn.bits := UInt(0)
  sramIn.valid := Bool(false)
  sramOut.ready := Bool(false)

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
        // state := s_SNAP1
        state := s_IDLE
        stateSnapCount := UInt(0)
        sramSnapCount := UInt(0)
      }
    }
    // Snapshotring inputs and registers
    is(s_SNAP1) {
      stateOut.ready := Bool(true)
      when(stateSnapCount >= UInt(buswidth)) {
        io.memOut.bits := stateSnapBuf
        io.memOut.valid := Bool(true)
        stateSnapCount := UInt(0)
      }
      when(stateOut.valid) {
        stateSnapBuf := Cat(stateSnapBuf, stateOut.bits)
        stateSnapCount := stateSnapCount + UInt(daisywidth)
      }.otherwise {
        state := s_IDLE
      }      
    }
    // Snapshotring SRAMs
    /*
    is(s_SNAP2) {
    }
    */
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
        state := s_IDLE
        io.hostIn.ready := Bool(false)
      }
    }
    is(s_PEEK) {
      val id = UInt(outputs.length) - peekCounter
      when(peekCounter.orR) {
        io.hostOut.valid := Bool(true)
        when(io.hostOut.ready) {
          io.hostOut.bits := outputBufs(id)
          peekCounter := peekCounter - UInt(1)
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
