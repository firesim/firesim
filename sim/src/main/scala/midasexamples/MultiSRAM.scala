// See LICENSE for license details

package firesim.midasexamples

import chisel3._
import chisel3.experimental.annotate

import freechips.rocketchip.config.Parameters

import midas.targetutils._

/** A module that wraps a multi-ported, synchronous-read memory.
  *
  * @see [[RegfileInner]] for an analogous module with an asynchronous-read memory
  */
class SRAMInner extends Module {
  val io = IO(new RegfileIO)
  val mem = SyncReadMem(21, UInt(64.W))
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

/** A DUT to demonstrate threading of set of identical [[SRAMInner]] instances. This
  * helps test the behavior of the threading optimization with synchronous-read memories.
  *
  * @see [[MultiRegfileDUT]] for an analogous target using` optimizable memories
  */
class MultiSRAMDUT(nCopies: Int) extends Module {
  val io = IO(new Bundle {
    val accesses = Vec(nCopies, new RegfileIO)
  })
  val rfs = Seq.fill(nCopies)(Module(new SRAMInner))
  rfs.zip(io.accesses).foreach {
    case (rf, rfio) =>
      rf.io <> rfio
      annotate(EnableModelMultiThreadingAnnotation(rf))
  }
}

/** A top-level target module that instantiates a small number of inner [[SRAMInner]] modules. This is used as part of
  * [[MultiSRAMF1Test]] to test for correct behavior with a directed random peek-poke test. If the module is threaded,
  * this can help ensure the correct behavior of the threading optimization. However, it does not directly test whether
  * threading is successfully applied, and a simulator that unexpectedly fails to be threaded may indeed behave correctly.
  *
  * @see [[MultiSRAMFMR]] for a test that helps ensure threading is actually applied
  * @see [[MultiRegfile]] for an analogous test that contains optimizable memories
  */
class MultiSRAM(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new MultiSRAMDUT(MultiRegfile.nCopiesToTest))

/** A top-level target module that instantiates a large number of inner [[SRAMInner]] modules. This is used as part of
  * [[MultiSRAMFMRF1Test]] to test whether the threading optimization is actually applied. By letting a simulator run
  * freely for many cycles, the observed FPGA-cycle-to-model-cycle ratio (FMR) can be compared to the expected slowdown
  * from multi-threading N instances.
  *
  * @see [[MultiRegfileFMR]] for an analogous test that contains optimizable memories
  */
class MultiSRAMFMR(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new MultiSRAMDUT(MultiRegfile.nCopiesToTime))
