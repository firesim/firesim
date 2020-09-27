// See LICENSE for license details

package firesim.midasexamples

import chisel3._
import chisel3.experimental.annotate

import freechips.rocketchip.config.Parameters

import midas.widgets.PeekPokeBridge
import midas.targetutils._

class SRAMInner extends Module {
  val io = IO(new RegfileIO)
  val mem = SyncReadMem(1024, UInt(64.W))
  io.reads.foreach {
    rp => rp.data := mem.read(rp.addr)
  }
  io.writes.foreach {
    wp =>
      val underlyingRW = mem(wp.addr)
      when (wp.en) {
        underlyingRW := wp.data
      } .otherwise {
        val unusedRD = Wire(UInt())
	unusedRD := underlyingRW
      }
  }
}

class MultiSRAMDUT extends Module {
  val nCopies = 4
  val io = IO(new Bundle {
    val accesses = Vec(nCopies, new RegfileIO)
  })
  val rfs = Seq.fill(nCopies)(Module(new SRAMInner))
  rfs.zip(io.accesses).foreach {
    case (rf, rfio) =>
      rf.io <> rfio
      annotate(FAMEModelAnnotation(rf))
      annotate(EnableModelMultiThreadingAnnotation(rf))
  }
}

class MultiSRAM(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new MultiSRAMDUT)
