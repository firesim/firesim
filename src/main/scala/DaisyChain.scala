package faee

import Chisel._

class RegChainIO(datawidth: Int, daisywidth: Int = 1) extends Bundle {
  val stall = Bool(INPUT)
  val dataIn = UInt(INPUT, datawidth)
  val regsIn = Valid(UInt(INPUT, daisywidth)).flip
  val regsOut = Decoupled(UInt(INPUT, daisywidth))
}

class RegChain(val datawidth: Int, daisywidth: Int = 1) extends Module {
  val io = new RegChainIO(datawidth, daisywidth)
  val regs = Vec.fill(datawidth) { Reg(UInt(width=daisywidth)) }
  val copied = Reg(next=io.stall)
  val counter = Reg(UInt(width=log2Up(datawidth)))
  val copyCond = io.stall && !copied
  val readCond = io.stall && copied && io.regsOut.fire()

  // Connect daisy chains
  io.regsOut.bits := regs(0)
  io.regsOut.valid := !counter.orR
  for (i <- 0 until datawidth) {
    when(copyCond) {
      regs(i) := io.dataIn(i)
    }
    when(readCond) {
      if (i < datawidth - 1)
        regs(i) := regs(i+1)
      else
        regs(i) := io.regsIn.bits
    }
  }

  // Counter logic
  when (io.stall && io.regsOut.fire() && !io.regsIn.fire()) {
    counter := counter - UInt(1)
  }.elsewhen(!io.stall) {
    counter := UInt(datawidth)
  }
}
