package faee

import Chisel._

class StateChainIO(datawidth: Int, daisywidth: Int) extends Bundle {
  val stall = Bool(INPUT)
  val data = UInt(INPUT, datawidth)
  val in = Valid(UInt(INPUT, daisywidth)).flip
  val out = Decoupled(UInt(INPUT, daisywidth))
}

class StateChain(datawidth: Int, daisywidth: Int = 1) extends Module {
  val io = new StateChainIO(datawidth, daisywidth)
  val regs = Vec.fill(datawidth) { Reg(UInt(width=daisywidth)) }
  val copied = Reg(next=io.stall)
  val counter = Reg(UInt(width=log2Up(datawidth+1)))
  val copyCond = io.stall && !copied
  val readCond = io.stall && copied && io.out.fire()

  // Connect daisy chains
  io.out.bits := regs(datawidth-1)
  io.out.valid := counter.orR && io.out.ready
  for (i <- 0 until datawidth) {
    when(copyCond) {
      regs(i) := io.data(i)
    }
    when(readCond) {
      if (i == 0)
        regs(i) := io.in.bits
      else
        regs(i) := regs(i-1)
    }
  }

  // Counter logic
  when(copyCond) {
    counter := UInt(datawidth)
  }
  when(readCond && counter.orR && !io.in.valid) {
    counter := counter - UInt(1)
  }
}
