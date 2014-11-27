package Designs

import Chisel._
import Daisy._
import mini._
import TestCommon._
import scala.collection.mutable.HashMap
import scala.io.Source

class CoreDaisyTests(c: DaisyShim[Core], args: Array[String]) extends DaisyTester(c, false) {
  val mem = HashMap[BigInt, BigInt]()
  var filename = ""
  var timeout = 0

  def parseOpts {
    for (arg <- args) {
      if (arg.substring(0, 9) == "+loadmem=") {
        filename = arg.substring(9)
      } else if (arg.substring(0, 12) == "+max-cycles=") {
        timeout = arg.substring(12).toInt
      }
    }
  }

  // def parseNibble(hex: Int) = if (hex >= 'a') hex - 'a' + 10 else hex - '0'

  def loadMem {
    val lines = Source.fromFile(filename).getLines
    for ((line, i) <- lines.zipWithIndex) {
      val base = (i * line.length) / 2
      var offset = 0
      for (k <- (line.length - 2) to 0 by -2) {
        val addr = BigInt(base+offset)
        val data = BigInt((parseNibble(line(k)) << 4) | parseNibble(line(k+1)))
        mem(addr) = data
        offset += 1
      }
    }
  }

  def read(addr: BigInt) = {
    var data = BigInt(0)
    for (i <- 0 until 4) {
      data |= mem(addr + i) << (8 * i)
    }
    data
  } 

  def write(addr: BigInt, data: BigInt, mask: BigInt) = {
    for (i <- 3 to 0 by -1 ; if ((mask >> i) & 0x1) > 0) {
      mem(addr + i) = (data >> (8 * i)) & 0xff
    }    
  }

  def runTests {
    poke(c.target.io.stall, 1)
    pokeAt(c.target.dpath.regFile, 0, 0)
    step(1)
    poke(c.target.io.stall, 0)
    do {
      val iaddr = peek(c.target.io.icache.addr)
      val daddr = (peek(c.target.io.dcache.addr) >> 2) << 2
      val data  = peek(c.target.io.dcache.din)
      val dwe   = peek(c.target.io.dcache.we)
      val ire   = peek(c.target.io.icache.re) == 1
      val dre   = peek(c.target.io.dcache.re) == 1

      step(1)

      if (dwe > 0) {
        write(daddr, data, dwe)
      } else if (ire) {
        val inst = read(iaddr)
        poke(c.target.io.icache.dout, inst)
      } else if (dre) {
        val data = read(daddr)
        poke(c.target.io.dcache.dout, data)
      }

      val pc     = peek(c.target.dpath.ew_pc).toString(16)
      val inst   = UInt(peek(c.target.dpath.ew_inst), 32)
      val wb_en  = peek(c.target.ctrl.io.ctrl.wb_en)
      val wb_val = if (wb_en == 1) peek(c.target.dpath.regWrite) else peekAt(c.target.dpath.regFile, rd(inst)) 
      println("[%s] %s -> RegFile[%d] = %s".format(pc, instStr(inst), rd(inst), wb_val.toString(16)))
    } while (peek(c.target.io.host.tohost) == 0 && t < timeout)
    val tohost = peek(c.target.io.host.tohost)
    val reason = if (t < timeout) "tohost = " + tohost else "timeout"
    ok = ok && (tohost == 1)
    println("*** %s *** (%s) after %d simulation cycles".format(if (ok) "PASSED" else "FAILED", reason, t))
  }

  parseOpts
  loadMem
  runTests
}
