
package firesim.firesim

import scala.collection.mutable.LinkedHashSet

import freechips.rocketchip.system.{TestGeneration, RocketTestSuite}

object BlockdevTestSuite extends RocketTestSuite {
  val envName = ""
  // base_dir is is defined in firesim's Makefrag
  val dir = "$(base_dir)/target-rtl/firechip/tests"
  val makeTargetName = "blkdev-tests"
  def kind = "blockdev"
  // Blockdev tests need an image, which complicates this
  def additionalArgs = "+blkdev-in-mem=8"
  override def toString = s"$makeTargetName = \\\n" +
    // Make variable with the binaries of the suite
    names.map(n => s"\t$n.riscv").mkString(" \\\n") + "\n" +
    // Variables with binary specific arguments
    names.map(n => s"$n.riscv_ARGS=$additionalArgs").mkString(" \n") +
    postScript

  override def postScript = s"""

$$(base_dir)/target-rtl/firechip/tests/%blkdev.riscv: $$(output_dir)/%blockdev.riscv.ext2
\tcd make -f $$(base_dir)/target-rtl/firechip/Makefile

""" + super.postScript

  val names = LinkedHashSet("blkdev", "big-blkdev")
}

