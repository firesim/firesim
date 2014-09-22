package faee

import Chisel._

abstract class DaisyTester[+T <: DaisyWrapper[Module]](c: T, isTrace: Boolean = true) extends Tester(c, isTrace) {
  val ioMap = DaisyBackend.ioMap

  override def poke(data: Bits, x: BigInt) {
    if (ioMap contains data) {
      super.poke(ioMap(data), x)
    } else {
      super.poke(data, x)
    }
  }

  override def peek(data: Bits) = {
    if (ioMap contains data) {
      super.peek(ioMap(data))
    } else {
      super.peek(data)
    }
  }

  override def expect(data: Bits, expected: BigInt) = {
    if (ioMap contains data) {
      super.expect(ioMap(data), expected)
    } else {
      super.expect(data, expected)
    }
  }

  def takeSteps(n: Int) {
    val clk = emulatorCmd("step %d".format(n))
  }

  def pokeSteps(n: Int) {
    while(peek(c.io.stepsIn.ready) == 0)
      takeSteps(1)
    poke(c.io.stepsIn.bits, n)
    poke(c.io.stepsIn.valid, 1)
    takeSteps(1)
    poke(c.io.stepsIn.valid, 0)
  }

  def daisyStep(n: Int) {
    pokeSteps(n)
    do {
      takeSteps(1)
    } while (peek(c.io.stall) == 0) 
  }

  override def step(n: Int = 1) {
    val target = t + n
    daisyStep(n)
    if (isTrace) println("STEP " + n + " -> " + target)
    t += n
  }  
}
