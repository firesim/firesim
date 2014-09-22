package faee

import Chisel._

class DaisyWrapperIO(io: Data, datawidth: Int) extends Bundle {
  val targetIO = io.asInstanceOf[Bundle].clone
  val stepsIn = Decoupled(UInt(width=datawidth)).flip
  val regsOut = Decoupled(UInt(width=1))
  // val sramOut = Decoupled(UInt(width=1))
  // val cntrOut = Decoupled(UInt(width=1))
}

object DaisyWrapper {
  def apply[T<:Module](c: => T) = { 
    // add custom transforms for daisy chains
    DaisyBackend.addTransforms()
    // instantiate DaisyWrapper
    Module(new DaisyWrapper(c))
  }
}

class DaisyWrapper[T :< Module](c: => T, val datawidth: Int = 32) extends Module {
  val target = Module(c)
  val io = new DaisyWrapperIO(target.io, datawidth)

  // Add step counters for simulation run or stall
  val stepCounter = Reg(UInt())
  when (stepCounter.orR) {
    stepCounter := stepCounter - UInt(1)
  }.elsewhen(io.stepsIn.valid) {
    stepCounter := io.stepsIn.bits
  }

  // Connect IOs
  io.stepsIn.ready := !stepCounter.orR
  io.targetIO <> target.io 
}

