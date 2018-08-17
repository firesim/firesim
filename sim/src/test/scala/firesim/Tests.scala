//See LICENSE for license details.
package firesim.firesim

import java.io.{File, FileWriter}

import scala.concurrent.{Future, Await, ExecutionContext}
import scala.sys.process.{stringSeqToProcess, ProcessLogger}
import scala.reflect.ClassTag

import chisel3.internal.firrtl.Port

import freechips.rocketchip.diplomacy._
import freechips.rocketchip.devices.debug.DebugIO
import freechips.rocketchip.system.{RocketTestSuite, BenchmarkTestSuite}
import freechips.rocketchip.system.TestGeneration._
import freechips.rocketchip.system.DefaultTestSuites._
import freechips.rocketchip.config.{Config, Parameters}

abstract class FireSimTestSuite(
    val generatorArgs: FireSimGeneratorArgs,
    simulationArgs: String,
    N: Int = 5) extends firesim.midasexamples.TestSuiteCommon with HasFireSimGeneratorUtilities {
  import scala.concurrent.duration._
  import ExecutionContext.Implicits.global

  // From HasFireSimGeneratorUtilities
  // For the firesim utilities to use the same directory as the test suite
  override lazy val testDir = genDir

  // From TestSuiteCommon
  val targetTuple = generatorArgs.tupleName
  val commonMakeArgs = Seq(s"DESIGN=${generatorArgs.topModuleClass}",
                           s"TARGET_CONFIG=${generatorArgs.targetConfigs}",
                           s"PLATFORM_CONFIG=${generatorArgs.platformConfigs}")
  override lazy val platform = hostParams(midas.Platform)

  def runTest(backend: String, name: String, debug: Boolean) = {
    behavior of s"${name} running on ${backend}"
    compileMlSimulator(backend, debug)
    if (isCmdAvailable(backend)) {
      it should "pass in MIDAS-level simulation" in {
        assert(make(s"${outDir.getAbsolutePath}/${name}.%s".format(if (debug) "vpd" else "out"), s"EMUL=${backend}") == 0)
      }
    }
  }

  //def runReplay(backend: String, replayBackend: String, name: String) = {
  //  val dir = (new File(outDir, backend)).getAbsolutePath
  //  (Seq("make", s"replay-$replayBackend",
  //       s"SAMPLE=${dir}/${name}.sample", s"output_dir=$dir") ++ makeArgs).!
  //}

  def runSuite(backend: String, debug: Boolean = false)(suite: RocketTestSuite) {
    // compile emulators
    behavior of s"${suite.makeTargetName} in $backend"
    if (isCmdAvailable(backend)) {
      assert((Seq("make", s"$backend%s".format(if (debug) "-debug" else "")) ++ commonMakeArgs).! == 0)
      val postfix = suite match {
        case s: BenchmarkTestSuite => ".riscv"
        case _ => ""
      }
      val results = suite.names.toSeq sliding (N, N) map { t => 
        val subresults = t map (name =>
          Future(name -> runTest(backend, s"$name$postfix", debug)))
        Await result (Future sequence subresults, Duration.Inf)
      }
      results.flatten foreach { case (name, exitcode) =>
        it should s"pass $name" in { assert(exitcode == 0) }
      }
      //replayBackends foreach { replayBackend =>
      //  if (platformParams(midas.EnableSnapshot) && isCmdAvailable("vcs")) {
      //    assert((Seq("make", s"vcs-$replayBackend") ++ makeArgs).! == 0) // compile vcs
      //    suite.names foreach { name =>
      //      it should s"replay $name in $replayBackend" in {
      //        assert(runReplay(backend, replayBackend, s"$name$postfix") == 0)
      //      }
      //    }
      //  } else {
      //    suite.names foreach { name =>
      //      ignore should s"replay $name in $backend"
      //    }
      //  }
      //}
    } else {
      ignore should s"pass $backend"
    }
  }

  clean
  mkdirs
  elaborateAndCompileWithMidas
  generateTestSuiteMakefrags
}

class RocketChipF1Tests extends FireSimTestSuite(
  FireSimGeneratorArgs("FireSimNoNIC", "FireSimRocketChipConfig", "FireSimConfig"),
  "`cat runtime.conf`"
)
{
  runTest("vcs", "rv64ui-p-simple", false)
  //runSuite("verilator")(benchmarks)
  //runSuite("vcs", true)(benchmarks)
}

/*class BoomF1Tests extends FireSimTestSuite(
  "FireBoom",
  new FireSimBoomConfig,
  new FireSimConfig,
  "+dramsim +mm_MEM_LATENCY=80 +mm_LLC_LATENCY=1 +mm_LLC_WAY_BITS=2 +mm_LLC_SET_BITS=12 +mm_LLC_BLOCK_BITS=6",
  snapshot = true
)
{
  runSuite("vcs", true)(benchmarks)
}*/
