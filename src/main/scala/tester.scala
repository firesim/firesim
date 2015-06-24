package strober

import Chisel._
import scala.collection.mutable.{ArrayBuffer, HashMap, LinkedHashMap, Queue => ScalaQueue}
import scala.io.Source

abstract class SimTester[+T <: Module](c: T, isTrace: Boolean) extends Tester(c, false) {
  val pokeMap = HashMap[Int, BigInt]()
  val peekMap = HashMap[Int, BigInt]()

  val inMap = transforms.inMap
  val outMap = transforms.outMap

  def pokeChannel(addr: Int, data: BigInt): Unit
  def peekChannel(addr: Int): BigInt

  def takeSteps(n: Int) {
    val clk = emulatorCmd("step %d".format(n))
  }

  def pokePort(port: Bits, x: BigInt) {
    assert(inMap contains port)
    if (isTrace) println("* POKE " + dumpName(port) + " <- " + x.toString(16) + " *")
    pokeMap(inMap(port)) = x 
  }
 
  def peekPort(port: Bits) = {
    assert(outMap contains port)
    assert(peekMap contains outMap(port))
    val value = peekMap(outMap(port))
    if (isTrace) println("* PEEK " + dumpName(port) + " -> " + value.toString(16) + " *")
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
    assert(peekMap contains outMap(port))
    val value = peekMap(outMap(port))
    val pass = value == expected 
    expect(pass, "* EXPECT " + dumpName(port) + " -> " + value.toString(16) + " == " + expected.toString(16))
  }

  override def step(n: Int) {
    if (isTrace) println("STEP " + n + " -> " + (t + n))
    for (i <- 0 until n) {
      for ((in, id) <- inMap) {
        pokeChannel(id, pokeMap getOrElse (id, 0))
      }
      peekMap.clear
      for ((out, id) <- outMap) {
        peekMap(id) = peekChannel(id)
      }
    }
    t += n
  }

  def init {
    // Consumes initial output tokens
    peekMap.clear
    for ((out, id) <- outMap) {
      peekMap(id) = peekChannel(id)
    }
  }
}

abstract class SimWrapperTester[+T <: SimWrapper[Module]](c: T, isTrace: Boolean = true) 
  extends SimTester(c, isTrace) {

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

  init
}

abstract class SimAXI4WrapperTester[+T <: SimAXI4Wrapper[SimNetwork]](c: T, isTrace: Boolean = true) 
  extends SimTester(c, isTrace) {
  lazy val inWidths = inMap map { case (k, v) => (v, k.needWidth) }
  lazy val outWidths = outMap map { case (k, v) => (v, k.needWidth) }

  def pokeChannel(addr: Int, data: BigInt) {
    val mask = (BigInt(1) << c.axiDataWidth) - 1
    val limit = if (addr == c.resetAddr) 1 else (inWidths(addr) - 1) / c.axiDataWidth + 1
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
    assert(peek(c.io.M_AXI.r.valid) == 0)
    data
  }

  private val mem = Array.fill(1<<23){0.toByte} // size = 8MB

  private def tickMem {
    if (peek(c.io.S_AXI.ar.valid) == 1) {
      // handle read address
      val ar = peek(c.io.S_AXI.ar.bits.addr).toInt & 0xffff
      val tag = peek(c.io.S_AXI.ar.bits.id)
      val len = peek(c.io.S_AXI.ar.bits.len).toInt
      val size = 1 << peek(c.io.S_AXI.ar.bits.size).toInt
      poke(c.io.S_AXI.ar.ready, 1)
      do {
        takeSteps(1)
      } while (peek(c.io.S_AXI.r.ready) == 0)
      poke(c.io.S_AXI.ar.ready, 0)
      // handle read data
      for (k <- 0 to len) {
        var data = BigInt(0)
        for (i <- 0 until size) {
          val addr = ar+k*(size+1)+i
          data |= (mem(addr) & 0xff) << (8*i)
        }
        poke(c.io.S_AXI.r.bits.data, data)
        poke(c.io.S_AXI.r.bits.id, tag)
        poke(c.io.S_AXI.r.bits.last, 1)
        poke(c.io.S_AXI.r.valid, 1)
        do {
          takeSteps(1)
        } while (peek(c.io.S_AXI.r.ready) == 0)
        poke(c.io.S_AXI.r.bits.last, 0)
        poke(c.io.S_AXI.r.valid, 0)
      }
    } else if (peek(c.io.S_AXI.aw.valid) == 1) {
      // handle write address
      val aw = peek(c.io.S_AXI.ar.bits.addr).toInt & 0xffff
      val len = peek(c.io.S_AXI.aw.bits.len).toInt
      val size = 1 << peek(c.io.S_AXI.aw.bits.size).toInt
      poke(c.io.S_AXI.aw.ready, 1)
      do {
        takeSteps(1)
      } while (peek(c.io.S_AXI.w.valid) == 0)
      poke(c.io.S_AXI.aw.ready, 0)
      // handle write data
      for (k <- 0 to len) {
        while (peek(c.io.S_AXI.w.valid) == 0) {
          takeSteps(1)
        }
        val data = peek(c.io.S_AXI.w.bits.data)
        for (i <- 0 until size) {
          val addr = aw+k*(size+1)+i
          mem(addr) = ((data >> (8*i)) & 0xff).toByte
        }
        poke(c.io.S_AXI.w.ready, 1)
        assert(k < len || peek(c.io.S_AXI.w.bits.last) == 1)
        takeSteps(1)
        poke(c.io.S_AXI.w.ready, 0)
      }
    } else {
      poke(c.io.S_AXI.ar.ready, 0)
      poke(c.io.S_AXI.r.bits.last, 0)
      poke(c.io.S_AXI.r.valid, 0)
      poke(c.io.S_AXI.aw.ready, 0)
      poke(c.io.S_AXI.w.ready, 0)
    }
  }

  private def parseNibble(hex: Int) = if (hex >= 'a') hex - 'a' + 10 else hex - '0'

  def loadMem(filename: String) {
    val lines = Source.fromFile(filename).getLines
    for ((line, i) <- lines.zipWithIndex) {
      val base = (i * line.length) / 2
      var offset = 0
      for (k <- (line.length - 2) to 0 by -2) {
        val data = ((parseNibble(line(k)) << 4) | parseNibble(line(k+1))).toByte
        mem(base+offset) = data
        offset += 1
      }
    }
  }

  override def step(n: Int) {
    if (MemIO.count > 0) {
      for (i <- 0 until n) {
        super.step(1)
        tickMem  
      }
    } else {
      super.step(n)
    }
  }

  pokeChannel(c.resetAddr, 0)
  init
}
