package strober

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashMap, LinkedHashMap, Queue => ScalaQueue}
import scala.io.Source

abstract class SimTester[+T <: Module](c: T, isTrace: Boolean) extends Tester(c, false) {
  val pokeMap = HashMap[Int, BigInt]()
  val peekMap = HashMap[Int, BigInt]()

  def inMap: Map[Bits, Int]
  def outMap: Map[Bits, Int]

  def pokeChannel(addr: Int, data: BigInt): Unit
  def peekChannel(addr: Int): BigInt

  def takeSteps(n: Int) {
    val clk = emulatorCmd("step %d".format(n))
  }

  def pokePort(port: Bits, x: BigInt) {
    assert(inMap contains port)
    if (isTrace) println("* POKE " + dumpName(port) + " <- " + x + " *")
    pokeMap(inMap(port)) = x 
  }
 
  def peekPort(port: Bits) = {
    assert(outMap contains port)
    val value = peekMap(outMap(port))
    if (isTrace) println("* PEEK " + dumpName(port) + " -> " + value + " *")
    value
  }

  override def expect(pass: Boolean, msg: String) = {
    if (isTrace) println(msg + (if (pass) " : PASS" else " : FAIL"))
    if (!pass && failureTime < 0) failureTime = t
    ok &= pass
    pass
  }
 
  def expectPort(port: Bits, expected: BigInt) = {
    assert(outMap contains port)
    val value = peekMap(outMap(port))
    val pass = value == expected 
    expect(pass, "* EXPECT " + dumpName(port) + " -> " + value + " == " + expected)
  }

  override def step(n: Int) {
    if (isTrace) println("STEP " + n + " -> " + (t + n))
    var exit = false
    for (i <- 0 until n) {
      for ((in, id) <- inMap) {
        if (pokeMap contains id) {
          pokeChannel(id, pokeMap(id))
        } else {
          pokeChannel(id, 0)
        }
      }
      peekMap.clear
      for ((out, id) <- outMap) {
        peekMap(id) = peekChannel(id)
      }
    }
    t += n
  }
}

abstract class SimWrapperTester[+T <: SimWrapper[Module]](c: T, isTrace: Boolean = true) 
  extends SimTester(c, isTrace) {
  val inMap = c.inMap
  val outMap = c.outMap

  def pokeChannel(addr: Int, data: BigInt) {
    while(peek(c.io.ins(addr).ready) == 0) {
      takeSteps(1)
    }
    poke(c.io.ins(addr).bits.data, data)
    poke(c.io.ins(addr).valid, 1)
    takeSteps(1)
    poke(c.io.ins(addr).valid, 0)
  }

  def peekChannel(addr: Int) = {
    while(peek(c.io.outs(addr).valid) == 0) {
      takeSteps(1)
    }
    val value = peek(c.io.outs(addr).bits.data)
    poke(c.io.outs(addr).ready, 1)
    takeSteps(1)
    poke(c.io.outs(addr).ready, 0)
    value
  }
}

abstract class SimAXI4WrapperTester[+T <: SimAXI4Wrapper[SimNetwork]](c: T, isTrace: Boolean = true) 
  extends SimTester(c, isTrace) {
  val inMap = c.inMap
  val outMap = c.outMap
  val inWidths = c.inMap map { case (k, v) => (v, k.needWidth) }
  val outWidths = c.outMap map { case (k, v) => (v, k.needWidth) }
  val mask = (BigInt(1) << c.axiDataWidth) - 1

  def pokeChannel(addr: Int, data: BigInt) {
    val limit = (inWidths(addr) - 1) / c.axiDataWidth + 1
    for (i <- limit - 1 to 0 by -1) {
      val maskedData = (data >> (i * c.axiDataWidth)) & mask
      do {
        poke(c.io.M_AXI.aw.bits.id, 0)
        poke(c.io.M_AXI.aw.bits.addr, addr << 2)
        poke(c.io.M_AXI.aw.valid, 1)
        poke(c.io.M_AXI.w.bits.data, maskedData)
        poke(c.io.M_AXI.w.valid, 1)
        takeSteps(1)
      } while (peek(c.io.M_AXI.aw.ready) == 0 || peek(c.io.M_AXI.w.ready) == 0)

      do {
        poke(c.io.M_AXI.aw.valid, 0)
        poke(c.io.M_AXI.w.valid, 0)
        takeSteps(1)
      } while (peek(c.io.M_AXI.b.valid) == 0)

      assert(peek(c.io.M_AXI.b.bits.id) == 0)
      poke(c.io.M_AXI.b.ready, 1)
      takeSteps(1)
      poke(c.io.M_AXI.b.ready, 0)
    }
  }

  def peekChannel(addr: Int) = {
    var data = BigInt(0)
    val limit = (outWidths(addr) - 1) / c.axiDataWidth + 1
    for (i <- 0 until limit) {
      while (peek(c.io.M_AXI.ar.ready) == 0) {
        takeSteps(1)
      }

      poke(c.io.M_AXI.ar.bits.addr, addr << 2)
      poke(c.io.M_AXI.ar.bits.id, 0)
      poke(c.io.M_AXI.ar.valid, 1)
      takeSteps(1)
      poke(c.io.M_AXI.ar.valid, 0)

      while (peek(c.io.M_AXI.r.valid) == 0) {
        takeSteps(1)
      }

      data |= peek(c.io.M_AXI.r.bits.data) << (i * c.axiDataWidth)
      assert(peek(c.io.M_AXI.r.bits.id) == 0)
      poke(c.io.M_AXI.r.ready, 1)
      takeSteps(1)
      poke(c.io.M_AXI.r.ready, 0)
    }
    data
  }
}
