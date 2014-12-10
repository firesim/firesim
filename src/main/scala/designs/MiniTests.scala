package Designs

import Chisel._
import Daisy._
import mini._
import TestCommon._

class CoreDaisyTests(c: DaisyShim[Core], args: Array[String]) extends DaisyTester(c, false) {
  def runTests(maxcycles: Int, verbose: Boolean) = {
    poke(c.target.io.stall, 1)
    pokeAt(c.target.dpath.regFile.regs, 0, 0)
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
        HexCommon.writeMem(daddr, data, dwe)
      } else if (ire) {
        val inst = HexCommon.readMem(iaddr)
        poke(c.target.io.icache.dout, inst)
      } else if (dre) {
        val data = HexCommon.readMem(daddr)
        poke(c.target.io.dcache.dout, data)
      }

      if (verbose) {
        val pc     = peek(c.target.dpath.ew_pc).toString(16)
        val inst   = UInt(peek(c.target.dpath.ew_inst), 32)
        val wb_en  = peek(c.target.ctrl.io.ctrl.wb_en)
        val wb_val = 
          if (wb_en == 1) peek(c.target.dpath.regWrite) 
          else peekAt(c.target.dpath.regFile.regs, rd(inst)) 
        println("[%s] %s -> RegFile[%d] = %s".format(
                pc, instStr(inst), rd(inst), wb_val.toString(16)))
      }
    } while (peek(c.target.io.host.tohost) == 0 && t < maxcycles)
    val tohost = peek(c.target.io.host.tohost)
    val reason = if (t < maxcycles) "tohost = " + tohost else "timeout"
    ok &= tohost == 1
    println("*** %s *** (%s) after %d simulation cycles".format(
            if (ok) "PASSED" else "FAILED", reason, t))
  }

  val (filename, maxcycles, verbose) = HexCommon.parseOpts(args)
  HexCommon.loadMem(filename)
  runTests(maxcycles, verbose)
}

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
  // slowLoadMem(filename)
  fastLoadMem(filename)
  runTests(maxcycles, verbose)
}

class TileReplayTests(c: Tile, args: Array[String]) extends ReplayTester(c) {
  private var memtag  = BigInt(0)
  private var memaddr = BigInt(0)
  private var memmask = BigInt(0)
  private var memcycles = -1
  def tickMem {
    if (memcycles > 0) {
      poke(c.io.mem.req_cmd.ready, 0)
      poke(c.io.mem.req_data.ready, 0)
      poke(c.io.mem.resp.valid, 0)
      memcycles -= 1
    } else if (memcycles < 0) {
      if (peek(c.io.mem.req_cmd.valid) == 1) {
        memtag  = peek(c.io.mem.req_cmd.bits.tag)
        memaddr = peek(c.io.mem.req_cmd.bits.addr)
        memmask = peek(c.io.mem.req_cmd.bits.mask)
        // Memread
        if (peek(c.io.mem.req_cmd.bits.rw) == 0) {
          memcycles = 10
          poke(c.io.mem.req_cmd.ready, 1)
        }
      }
      if (peek(c.io.mem.req_data.valid) == 1) {
        val data = peek(c.io.mem.req_data.bits.data)
        poke(c.io.mem.req_cmd.ready, 1)
        poke(c.io.mem.req_data.ready, 1)
        HexCommon.writeMem(memaddr, data, memmask)
        memcycles = 5
      }
    } else {
      if (peek(c.io.mem.resp.ready) == 1) {
        val read = HexCommon.readMem(memaddr)
        poke(c.io.mem.resp.bits.data, read)
        poke(c.io.mem.resp.bits.tag, memtag)
        poke(c.io.mem.resp.valid, 1)
      }
      memcycles -= 1
    }
  }

  override def replayMem(addr: BigInt, data: BigInt) {
    HexCommon.writeMem(addr, data)
  }

  override def runTests(args: Any*) {
    val maxcycles = args(0) match { case x: Int => x }
    val verbose = args(1) match { case x: Boolean => x }
    pokeAt(c.core.dpath.regFile.regs, 0, 0)
    do {
      tickMem
      step(1)
      if (verbose) {
        val pc     = peek(c.core.dpath.ew_pc)
        val inst   = UInt(peek(c.core.dpath.ew_inst), 32)
        val wb_en  = peek(c.core.ctrl.io.ctrl.wb_en)
        val wb_val = 
          if (wb_en == 1) peek(c.core.dpath.regWrite) 
          else peekAt(c.core.dpath.regFile.regs, rd(inst)) 
        println("[%h] %s -> RegFile[%d] = %h".format(
                pc, instStr(inst), rd(inst), wb_val))
      }
    } while (peek(c.io.htif.host.tohost) == 0 && t < maxcycles)

    val tohost = peek(c.io.htif.host.tohost)
    val reason = if (t < maxcycles) "tohost = " + tohost else "timeout"
    ok &= tohost == 1
    println("*** %s *** (%s) after %d simulation cycles".format(
            if (ok) "PASSED" else "FAILED", reason, t))
  }

  val (filename, maxcycles, verbose) = HexCommon.parseOpts(args)
  loadSnap(filename, maxcycles, verbose)  
}

