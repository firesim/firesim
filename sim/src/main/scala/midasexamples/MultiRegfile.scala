// See LICENSE for license details

package firesim.midasexamples

import chisel3._
import chisel3.experimental.annotate

import freechips.rocketchip.config.Parameters

import midas.targetutils._


/** A module that wraps a multi-ported, asynchronous-read memory that is suitable for
  * optimization by substitution with a multi-ported memory model.
  *
  * @see [[SRAMInner]] for an analogous module with a synchronous-read memory
  */
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

/** A DUT to demonstrate threading of set of identical [[RegfileInner]] instances, each
  * containing a multi-ported memory targeted for replacement by the multi-cycle memory
  * model optimization. This module is useful to test the composability of the two
  * optimizations, and it is parameterizable by the number of inner instances.
  *
  * @see [[MultiSRAMDUT]] for an analogous target lacking optimizable memories
  */
class MultiRegfileDUT(nCopies: Int) extends Module {
  val io = IO(new Bundle {
    val accesses = Vec(nCopies, new RegfileIO)
  })
  val rfs = Seq.fill(nCopies)(Module(new RegfileInner))
  rfs.zip(io.accesses).foreach {
    case (rf, rfio) =>
      rf.io <> rfio
      annotate(EnableModelMultiThreadingAnnotation(rf))
  }
}

object MultiRegfile {
  val nCopiesToTest = 5
  val nCopiesToTime = 17
}

/** A top-level target module that instantiates a small number of inner regfiles. This is used as part of [[MultiRegfileF1Test]]
  * to test for correct behavior with a directed random peek-poke test. If the module is optimized, this can help ensure the correct
  * behavior of the optimizations. However, it does not directly test whether the optimizations are successfully applied, and
  * a simulator that is unexpectedly missing the optimizations may indeed behave correctly.
  *
  * @see [[MultiRegfileFMR]] for a test that helps ensure optimizations are actually applied
  * @see [[MultiSRAM]] for an analogous test that lacks optimizable memories
  */
class MultiRegfile(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new MultiRegfileDUT(MultiRegfile.nCopiesToTest))

/** A top-level target module that instantiates a large number of inner regfiles. This is used as part of [[MultiRegfileFMRF1Test]]
  * to test whether optimizations are actually applied. By letting a simulator run freely for many cycles, the observed FPGA-cycle-
  * to-model-cycle ratio (FMR) can be compared to the expected slowdown for the optimizaions.
  *
  * @see [[MultiSRAMFMR]] for an analogous test that lacks optimizable memories
  */
class MultiRegfileFMR(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new MultiRegfileDUT(MultiRegfile.nCopiesToTime))
