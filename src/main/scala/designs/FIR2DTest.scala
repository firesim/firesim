package Designs

import Chisel._
import Daisy._

class FIR2DTests(c: FIR2D, elementSize: Int, lineSize: Int, kernelSize: Int) extends Tester(c, false) {
  poke(c.io.in.valid, 1)
  poke(c.io.out.ready, 0)
  for (i <- 0 until 100*((kernelSize-1)*lineSize)) {
    // expect(c.io.in.ready, 1)
    poke(c.io.in.bits, i % ((kernelSize-1)*lineSize))
    step(1)
  }
}

class FIR2DDaisyTests(c: DaisyShim[FIR2D], elementSize: Int, lineSize: Int, kernelSize: Int) extends DaisyTester(c, false) {
  poke(c.target.io.in.valid, 1)
  poke(c.target.io.out.ready, 0)
  for (i <- 0 until 100*((kernelSize-1)*lineSize)) {
    // expect(c.target.io.in.ready, 1)
    poke(c.target.io.in.bits, i % ((kernelSize-1)*lineSize))
    step(1)
  }
}
