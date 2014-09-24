package faee

import Chisel._

class StateChainIO(datawidth: Int, daisywidth: Int) extends Bundle {
  val stall = Bool(INPUT)
  val data = UInt(INPUT, datawidth)
  val in = Valid(UInt(INPUT, daisywidth)).flip
  val out = Decoupled(UInt(INPUT, daisywidth))
}

class StateChain(val datawidth: Int, daisywidth: Int = 1) extends Module {
  val io = new StateChainIO(datawidth, daisywidth)
  val regs = Vec.fill(datawidth) { Reg(UInt(width=daisywidth)) }
  val copied = Reg(next=io.stall)
  val counter = Reg(UInt(width=log2Up(datawidth)))
  val copyCond = io.stall && !copied
  val readCond = io.stall && copied && io.out.fire()

  // Connect daisy chains
  io.out.bits := regs(0)
  io.out.valid := !counter.orR
  for (i <- 0 until datawidth) {
    when(copyCond) {
      regs(i) := io.data(i)
    }
    when(readCond) {
      if (i < datawidth - 1)
        regs(i) := regs(i+1)
      else
        regs(i) := io.in.bits
    }
  }

  // Counter logic
  when(io.stall && io.out.fire() && !io.in.fire()) {
    counter := counter - UInt(1)
  }.elsewhen(!io.stall) {
    counter := UInt(datawidth)
  }
}
