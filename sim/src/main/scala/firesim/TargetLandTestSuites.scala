
package firesim.firesim

import scala.collection.mutable.LinkedHashSet

import freechips.rocketchip.system.{TestGeneration, RocketTestSuite}

class BlockdevTestSuite(prefix: String, val names: LinkedHashSet[String]) extends RocketTestSuite {
  val envName = ""
  // base_dir is is defined in firesim's Makefrag
  val dir = "$(fc_test_dir)"
  val makeTargetName = prefix + "-blkdev-tests"
  def kind = "blockdev"
  // Blockdev tests need an image, which complicates this
  def additionalArgs = "+blkdev-in-mem=128"
  override def toString = s"$makeTargetName = \\\n" +
    // Make variable with the binaries of the suite
    names.map(n => s"\t$n.riscv").mkString(" \\\n") + "\n\n" +
    // Variables with binary specific arguments
    names.map(n => s"$n.riscv_ARGS=$additionalArgs").mkString(" \n") +
    postScript
}

object FastBlockdevTests extends BlockdevTestSuite("fast", LinkedHashSet("blkdev"))
object AllBlockdevTests extends BlockdevTestSuite("all", LinkedHashSet("blkdev", "big-blkdev"))

