package faee

import Chisel._
import TutorialExamples._

class GCDDaisyTests(c: DaisyWrapper[GCD]) extends DaisyTester(c) {
  val (a, b, z) = (64, 48, 16)
  do {
    val first = if (t == 0) 1 else 0
    poke(c.target.io.a, a)
    poke(c.target.io.b, b)
    poke(c.target.io.e, first)
    step(1)
  } while (t <= 1 || peek(c.target.io.v) == 0)
  expect(c.target.io.z, z)
}
