// See LICENSE for license details

package firesim.midasexamples

import chisel3._
import chisel3.experimental.annotate

import freechips.rocketchip.config.Parameters

import midas.widgets.PeekPokeBridge
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

class Regfile extends Module {
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

class MultiRegfileDUT extends Module {
  val nCopies = 4
  val io = IO(new Bundle {
    val accesses = Vec(nCopies, new RegfileIO)
  })
  val rfs = Seq.fill(nCopies)(Module(new Regfile))
  rfs.zip(io.accesses).foreach {
    case (rf, rfio) =>
      rf.io <> rfio
      annotate(FAMEModelAnnotation(rf))
      annotate(EnableModelMultiThreadingAnnotation(rf))
  }
}

class MultiRegfile(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new MultiRegfileDUT)
