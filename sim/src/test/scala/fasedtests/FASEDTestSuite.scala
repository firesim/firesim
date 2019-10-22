//See LICENSE for license details.
package firesim.fasedtests

import java.io.File

import scala.concurrent.{Future, Await, ExecutionContext}
import scala.sys.process.{stringSeqToProcess, ProcessLogger}
import scala.util.Random

import freechips.rocketchip.diplomacy._
import freechips.rocketchip.system.{RocketTestSuite, BenchmarkTestSuite}
import freechips.rocketchip.system.TestGeneration._
import freechips.rocketchip.system.DefaultTestSuites._

import firesim.util.GeneratorArgs
import firesim.configs.LlcKey

abstract class FASEDTest(
    topModuleClass: String,
    targetConfigs: String,
    platformConfigs: String,
    N: Int = 8
  ) extends firesim.TestSuiteCommon with firesim.util.HasFireSimGeneratorUtilities {
  import scala.concurrent.duration._
  import ExecutionContext.Implicits.global

  val longName = names.topModuleProject + "." + names.topModuleClass + "." + names.configs

  lazy val generatorArgs = GeneratorArgs(
    midasFlowKind = "midas",
    targetDir = "generated-src",
    topModuleProject = "firesim.fasedtests",
    topModuleClass = topModuleClass,
    targetConfigProject = "firesim.fasedtests",
    targetConfigs = targetConfigs,
    platformConfigProject = "firesim.fasedtests",
    platformConfigs = platformConfigs)

  // From TestSuiteCommon
  val targetTuple = generatorArgs.tupleName
  val commonMakeArgs = Seq( "TARGET_PROJECT=fasedtests",
                           s"DESIGN=${generatorArgs.topModuleClass}",
                           s"TARGET_CONFIG=${generatorArgs.targetConfigs}",
                           s"PLATFORM_CONFIG=${generatorArgs.platformConfigs}")

  def invokeMlSimulator(backend: String, debug: Boolean, args: Seq[String]) = {
    make((s"run-${backend}%s".format(if (debug) "-debug" else "") +: args):_*)
  }

  def runTest(backend: String, debug: Boolean, args: Seq[String] = Nil, name: String = "pass") = {
    compileMlSimulator(backend, debug)
    if (isCmdAvailable(backend)) {
      it should name in {
        assert(invokeMlSimulator(backend, debug, args) == 0)
      }
    }
  }
  def runTests() {
    runTest("verilator", false)
    //runTest("vcs", true)
  }

  clean
  mkdirs
  elaborate
  behavior of s"FASED Instance configured with ${platformConfigs} driven by target: ${topModuleClass}"
  runTests()
}

class AXI4FuzzerLBPTest extends FASEDTest("AXI4Fuzzer", "DefaultConfig", "DefaultF1Config")
class AXI4FuzzerFCFSTest extends FASEDTest("AXI4Fuzzer", "FCFSConfig", "DefaultF1Config")
class AXI4FuzzerFRFCFSTest extends FASEDTest("AXI4Fuzzer", "FRFCFSConfig", "DefaultF1Config")
class AXI4FuzzerLLCDRAMTest extends FASEDTest("AXI4Fuzzer", "LLCDRAMConfig", "DefaultF1Config") {
  override def runTests = {
    // Check that the memory model uses the correct number of MSHRs
    val maxMSHRs = targetParams(LlcKey).get.mshrs.max
    val runtimeValues = Set((maxMSHRs +: Seq.fill(3)(Random.nextInt(maxMSHRs - 1) + 1)):_*).toSeq
    runtimeValues.foreach({ runtimeMSHRs: Int =>
      val plusArgs = Seq(s"+mm_llc_activeMSHRs=${runtimeMSHRs}",
                     s"+expect_llc_peakMSHRsUsed=${runtimeMSHRs}")
      val extraSimArgs = Seq(s"""EXTRA_SIM_ARGS='${plusArgs.mkString(" ")}' """)
      runTest("verilator", false, args = extraSimArgs, name = s"correctly execute and use at most ${runtimeMSHRs} MSHRs")
     })
  }
}
