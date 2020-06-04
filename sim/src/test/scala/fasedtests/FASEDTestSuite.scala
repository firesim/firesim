//See LICENSE for license details.
package firesim.fasedtests

import java.io.File

import scala.concurrent.{Future, Await, ExecutionContext}
import scala.util.Random
import org.scalatest.Suites

import freechips.rocketchip.diplomacy._
import freechips.rocketchip.system.{RocketTestSuite, BenchmarkTestSuite}
import freechips.rocketchip.system.TestGeneration._
import freechips.rocketchip.system.DefaultTestSuites._

import firesim.configs.LlcKey

abstract class FASEDTest(
    topModuleClass: String,
    targetConfigs: String,
    platformConfigs: String,
    N: Int = 8
  ) extends firesim.TestSuiteCommon {
  import scala.concurrent.duration._
  import ExecutionContext.Implicits.global

  val targetTuple = s"$topModuleClass-$targetConfigs-$platformConfigs"
  val commonMakeArgs = Seq( "TARGET_PROJECT=fasedtests",
                           s"DESIGN=${topModuleClass}",
                           s"TARGET_CONFIG=${targetConfigs}",
                           s"PLATFORM_CONFIG=${platformConfigs}")

  def invokeMlSimulator(backend: String, debug: Boolean, args: Seq[String]) = {
    make((s"run-${backend}%s".format(if (debug) "-debug" else "") +: args):_*)
  }

  def runTest(backend: String, debug: Boolean, args: Seq[String] = Nil, name: String = "pass") = {
    compileMlSimulator(backend, debug)
    if (isCmdAvailable(backend)) {
      it should s"run on $backend" in {
        assert(invokeMlSimulator(backend, debug, args) == 0)
      }
    }
  }
  def runTests() {
    runTest("verilator", false)
    //runTest("vcs", true)
  }

  clean
  behavior of s"FASED Instance configured with ${platformConfigs} driven by target: ${topModuleClass}"
  runTest("verilator", false)
}

class AXI4FuzzerLBPTest extends FASEDTest("AXI4Fuzzer", "DefaultConfig", "DefaultF1Config")
class AXI4FuzzerMultiChannelTest extends FASEDTest("AXI4Fuzzer", "FuzzMask3FFF_QuadFuzzer_QuadChannel_DefaultConfig", "DefaultF1Config")
class AXI4FuzzerFCFSTest extends FASEDTest("AXI4Fuzzer", "FCFSConfig", "DefaultF1Config")
class AXI4FuzzerFRFCFSTest extends FASEDTest("AXI4Fuzzer", "FRFCFSConfig", "DefaultF1Config")
class AXI4FuzzerLLCDRAMTest extends FASEDTest("AXI4Fuzzer", "LLCDRAMConfig", "DefaultF1Config") {
  //override def runTests = {
  //  // Check that the memory model uses the correct number of MSHRs
  //  val maxMSHRs = targetParams(LlcKey).get.mshrs.max
  //  val runtimeValues = Set((maxMSHRs +: Seq.fill(3)(Random.nextInt(maxMSHRs - 1) + 1)):_*).toSeq
  //  runtimeValues.foreach({ runtimeMSHRs: Int =>
  //    val plusArgs = Seq(s"+mm_llc_activeMSHRs=${runtimeMSHRs}",
  //                   s"+expect_llc_peakMSHRsUsed=${runtimeMSHRs}")
  //    val extraSimArgs = Seq(s"""EXTRA_SIM_ARGS='${plusArgs.mkString(" ")}' """)
  //    runTest("verilator", false, args = extraSimArgs, name = s"correctly execute and use at most ${runtimeMSHRs} MSHRs")
  //   })
  //}
}

// Generate a target memory system that uses the whole host memory system.
class BaselineMultichannelTest extends FASEDTest(
    "AXI4Fuzzer",
    "AddrBits22_QuadFuzzer_DefaultConfig",
    "AddrBits22_SmallQuadChannelHostConfig") {
  runTest("vcs", true)
}

// Checks that id-reallocation works for platforms with limited ID space
class NarrowIdConstraint extends FASEDTest("AXI4Fuzzer", "DefaultConfig", "ConstrainedIdHostConfig")
class AXI4FuzzerZC706LBPTest extends FASEDTest("AXI4Fuzzer", "DefaultConfig", "DefaultZC706Config")
class AXI4FuzzerZedboardTest extends FASEDTest("AXI4Fuzzer", "DefaultConfig", "DefaultZedboardConfig")

// Suite Collections for CI
class CIGroupA extends Suites(
  new AXI4FuzzerLBPTest,
  new AXI4FuzzerFRFCFSTest
)

class CIGroupB extends Suites(
  new AXI4FuzzerLLCDRAMTest,
  new NarrowIdConstraint
)

