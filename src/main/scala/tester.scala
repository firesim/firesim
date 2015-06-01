package strober

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashMap, LinkedHashMap, Queue => ScalaQueue}
import scala.io.Source

abstract class SimWrapperTester[+T <: SimWrapper[Module]](c: T, isTrace: Boolean = true) extends Tester(c, false) {
  val target = c.target
  val pokeMap = HashMap[Int, BigInt]()
  val peekMap = HashMap[Int, BigInt]()

  def takeSteps(n: Int) {
    val clk = emulatorCmd("step %d".format(n))
  }

  def pokePort(port: Bits, x: BigInt) {
    assert(c.inMap contains port)
    if (isTrace) println("* POKE " + dumpName(port) + " <- " + x + " *")
    pokeMap(c.inMap(port)) = x 
  }
 
  def peekPort(port: Bits) = {
    assert(c.outMap contains port)
    val value = peekMap(c.outMap(port))
    if (isTrace) println("* PEEK " + dumpName(port) + " -> " + value + " *")
    value
  }

  def expectPort(port: Bits, expected: BigInt) = {
    assert(c.outMap contains port)
    val value = peekMap(c.outMap(port))
    val pass = value == expected 
    if (isTrace) println("* EXPECT " + dumpName(port) + " -> " + value + " == " + expected + 
      (if (pass) ", PASS" else ", FAIL"))
    pass
  }

  override def step(n: Int) {
    if (isTrace) println("STEP " + n + " -> " + (t + n))
    var exit = false
    for (i <- 0 until n) {
      do {
        exit = true
        takeSteps(1)
        for ((in, id) <- c.inMap) {
          exit &= peek(c.io.ins(id).ready) == 1
        }
      } while(!exit)

      for ((in, id) <- c.inMap) {
        assert(pokeMap contains id)
        poke(c.io.ins(id).bits.payload, pokeMap(id))
        poke(c.io.ins(id).valid, 1)
      }
      takeSteps(1)
      for ((in, id) <- c.inMap) {
        poke(c.io.ins(id).valid, 0)
      }
      do {
        exit = true
        takeSteps(1)
        for ((out, id) <- c.outMap) {
          exit &= peek(c.io.outs(id).valid) == 1
        }
      } while(!exit)

      peekMap.clear
      for ((out, id) <- c.outMap) {
        peekMap(id) = peek(c.io.outs(id).bits.payload)
        poke(c.io.outs(id).ready, 1)
      }
      takeSteps(1)
      for ((out, id) <- c.outMap) {
        poke(c.io.outs(id).ready, 0) 
      }
    }
    t += n
  }
}
