//See LICENSE for license details.
package firesim.fasedtests

import java.io.File

import scala.concurrent.{Future, Await, ExecutionContext}
import scala.sys.process.{stringSeqToProcess, ProcessLogger}

import freechips.rocketchip.diplomacy._
import freechips.rocketchip.system.{RocketTestSuite, BenchmarkTestSuite}
import freechips.rocketchip.system.TestGeneration._
import freechips.rocketchip.system.DefaultTestSuites._

import firesim.util.GeneratorArgs

abstract class FASEDTest(
    topModuleClass: String,
    targetConfigs: String,
    platformConfigs: String,
    N: Int = 8
  ) extends firesim.midasexamples.TestSuiteCommon with GeneratorUtils {
  import scala.concurrent.duration._
  import ExecutionContext.Implicits.global

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
  override lazy val platform = hostParams(midas.Platform)

  def invokeMlSimulator(backend: String, debug: Boolean) = {
    make(s"run-${backend}%s".format(if (debug) "-debug" else ""))
  }

  def runTest(backend: String, debug: Boolean) = {
    behavior of s"when running on ${backend} in MIDAS-level simulation"
    compileMlSimulator(backend, debug)
    if (isCmdAvailable(backend)) {
      it should s"pass" in {
        assert(invokeMlSimulator(backend, debug) == 0)
      }
    }
  }

  clean
  mkdirs
  elaborateAndCompileWithMidas
  runTest("verilator", false)
  //runTest("vcs", true)
}

class AXI4FuzzerLBPTest extends FASEDTest("AXI4Fuzzer", "DefaultConfig", "DefaultF1Config")
class AXI4FuzzerFCFSTest extends FASEDTest("AXI4Fuzzer", "DefaultConfig", "FCFSConfig")
class AXI4FuzzerFRFCFSTest extends FASEDTest("AXI4Fuzzer", "DefaultConfig", "FRFCFSConfig")
class AXI4FuzzerLLCDRAMTest extends FASEDTest("AXI4Fuzzer", "DefaultConfig", "LLCDRAMConfig")
