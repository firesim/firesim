package faee

import Chisel._

class DaisyWrapperIO[T <: Data](io: T, datawidth: Int = 32) extends Bundle {
  val targetIO: T = io.clone
  val stall = Bool(OUTPUT)
  val stepsIn = Decoupled(UInt(width=datawidth)).flip
  val regsOut = Decoupled(UInt(width=1))
  // val sramOut = Decoupled(UInt(width=1))
  // val cntrOut = Decoupled(UInt(width=1))
}

object DaisyWrapper {
  def apply[T <: Module](c: => T) = Module(new DaisyWrapper(c))
}

class DaisyWrapper[+T <: Module](c: => T, val datawidth: Int = 32) extends Module {
  val target = Module(c)
  val io = new DaisyWrapperIO(target.io, datawidth)

  // Add step counters for simulation run or stall
  val stepCounter = Reg(UInt())
  val fire = stepCounter.orR
  val fireDelay = Reg(next=fire)

  when (fire) {
    stepCounter := stepCounter - UInt(1)
  }.elsewhen(io.stepsIn.valid) {
    stepCounter := io.stepsIn.bits
  }

  // Connect IOs
  io.targetIO <> target.io
  io.stall := !fire && !fireDelay
  io.stepsIn.ready := io.stall

  // add custom transforms for daisy chains
  DaisyBackend.addTransforms()
}
