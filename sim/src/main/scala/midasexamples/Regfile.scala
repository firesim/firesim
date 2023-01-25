// See LICENSE for license details

package firesim.midasexamples

import chisel3._
import chisel3.experimental.annotate

import freechips.rocketchip.config.Parameters

import midas.targetutils._

class RegfileRead extends Bundle {
  val addr = Input(UInt(5.W))
  val data = Output(UInt(64.W))
}

class RegfileWrite extends Bundle {
  val addr = Input(UInt(5.W))
  val data = Input(UInt(64.W))
  val en = Input(Bool())
}

class RegfileIO extends Bundle {
  val reads = Vec(2, new RegfileRead)
  val writes = Vec(2, new RegfileWrite)
}

class RegfileDUT extends Module {
  val io = IO(new RegfileIO)
  val mem = Mem(1024, UInt(64.W))
  annotate(MemModelAnnotation(mem))
  io.reads.foreach {
    rp => rp.data := mem.read(RegNext(rp.addr))
  }
  io.writes.foreach {
    wp => when (wp.en) { mem.write(wp.addr, wp.data) }
  }
}

class Regfile(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new RegfileDUT)
