package Designs

import Chisel._
import Daisy._
import mini._
import TestCommon._

class TileDaisyTests(c: DaisyShim[Tile], args: Array[String]) extends DaisyTester(c, false) {
  def runTests(maxcycles: Int, verbose: Boolean) {
    pokeAt(c.target.core.dpath.regFile.regs, 0, 0)
    do {
      step(10)
      if (verbose) {
        val pc     = peek(c.target.core.dpath.ew_pc)
        val inst   = UInt(peek(c.target.core.dpath.ew_inst), 32)
        val wb_en  = peek(c.target.core.ctrl.io.ctrl.wb_en)
        val wb_val = 
          if (wb_en == 1) peek(c.target.core.dpath.regWrite) 
          else peekAt(c.target.core.dpath.regFile.regs, rd(inst)) 
        println("[%h] %s -> RegFile[%d] = %h".format(
                pc, instStr(inst), rd(inst), wb_val))
      }
    } while (peek(c.target.io.htif.host.tohost) == 0 && t < maxcycles)

    val tohost = peek(c.target.io.htif.host.tohost)
    val reason = if (t < maxcycles) "tohost = " + tohost else "timeout"
    ok &= tohost == 1
    println("*** %s *** (%s) after %d simulation cycles".format(
            if (ok) "PASSED" else "FAILED", reason, t))
  }

  val (filename, maxcycles, verbose) = HexCommon.parseOpts(args)
  slowLoadMem(filename)
  runTests(maxcycles, verbose)
}
