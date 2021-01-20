// See LICENSE for license details

package firesim.midasexamples

import chisel3._
import chisel3.experimental.annotate

import freechips.rocketchip.config.Parameters

import midas.widgets.PeekPokeBridge
import midas.targetutils._

class RegfileInner extends Module {
  val io = IO(new RegfileIO)
  val mem = Mem(21, UInt(64.W))
  annotate(MemModelAnnotation(mem))
  io.reads.foreach {
    rp => rp.data := mem.read(RegNext(rp.addr))
  }
  io.writes.foreach {
    wp => when (wp.en) { mem.write(wp.addr, wp.data) }
  }
}

object MultiRegfile {
  val nCopies = 5
}

class MultiRegfileDUT extends Module {
  val io = IO(new Bundle {
    val accesses = Vec(MultiRegfile.nCopies, new RegfileIO)
  })
  val rfs = Seq.fill(MultiRegfile.nCopies)(Module(new RegfileInner))
  rfs.zip(io.accesses).foreach {
    case (rf, rfio) =>
      rf.io <> rfio
      annotate(EnableModelMultiThreadingAnnotation(rf))
  }
}

class MultiRegfile(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new MultiRegfileDUT)
