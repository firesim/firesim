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
    targetName: String,
    targetConfig: Config,
    platformConfig: Config,
    simulationArgs: String,
    N: Int = 5) extends firesim.midasexamples.TestSuiteCommon with HasTestSuites {
  import scala.concurrent.duration._
  import ExecutionContext.Implicits.global

  // From TestSuiteCommon
  val targetTuple = s"$targetName-$targetConfig-$platformConfig"
  val commonMakeArgs = Seq(s"DESIGN=$targetName",
                           s"TARGET_CONFIG=$targetConfig",
                           s"PLATFORM_CONFIG=$platformConfig")

  val platformParams = platformConfig.toInstance//.alterPartial({ case midas.EnableSnapshot => snapshot })
  val platformConfigName = platformConfig.getClass.getSimpleName
  val platform = platformParams(midas.Platform)

  val targetParams = targetConfig.toInstance
  val targetConfigName = targetConfig.getClass.getSimpleName

  implicit val valName = ValName(targetName)
  lazy val target = targetName match {
    case "FireSim"  => LazyModule(new FireSimNoNIC()(targetParams)).module
    case "FireBoom" => LazyModule(new FireBoomNoNIC()(targetParams)).module
  }
  val circuit = chisel3.Driver.elaborate(() => target)
  val chirrtl = firrtl.Parser.parse(chisel3.Driver.emit(circuit))
  val annos = circuit.annotations map { _.toFirrtl }
  val ports = target.getPorts flatMap {
    case Port(id: DebugIO, _) => None
    case Port(id: AutoBundle, _) => None // What the hell is AutoBundle?
    case otherPort => Some(otherPort.id)
  }
  val customPasses = Seq(
    firesim.passes.AsyncResetRegPass,
    firesim.passes.PlusArgReaderPass
  )
  midas.MidasCompiler(chirrtl, annos, ports, genDir, None, customPasses)(platformParams)
  //if (platformParams(midas.EnableSnapshot))
  //  strober.replay.Compiler(chirrtl, annos, ports, genDir, lib)
  addTestSuites(targetParams)

  val makefrag = new FileWriter(new File(genDir, "firesim.d"))
  makefrag write generateMakefrag
  makefrag.close

  lazy val makeArgs = Seq(
    s"PLATFORM=$platformName",
    s"DESIGN=$targetName",
    s"TARGET_CONFIG=$targetConfigName",
    s"PLATFORM_CONFIG=$platformConfigName",
    s"SW_SIM_ARGS=${simulationArgs}")

  def runTest(backend: String, name: String, debug: Boolean) = {
    val dir = (new File(outDir, backend)).getAbsolutePath
    (Seq("make", s"${dir}/${name}.%s".format(if (debug) "vpd" else "out"),
         s"EMUL=$backend", s"output_dir=$dir") ++ makeArgs).!
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
      assert((Seq("make", s"$backend%s".format(if (debug) "-debug" else "")) ++ makeArgs).! == 0)
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
}

/*class RocketChipF1Tests extends FireSimTestSuite(
  "FireSim",
  new FireSimRocketChipConfig,
  new FireSimConfig,
  "+dramsim +mm_MEM_LATENCY=80 +mm_LLC_LATENCY=1 +mm_LLC_WAY_BITS=2 +mm_LLC_SET_BITS=12 +mm_LLC_BLOCK_BITS=6",
  snapshot = true
)
{
  runSuite("verilator")(benchmarks)
  runSuite("vcs", true)(benchmarks)
}

class BoomF1Tests extends FireSimTestSuite(
  "FireBoom",
  new FireSimBoomConfig,
  new FireSimConfig,
  "+dramsim +mm_MEM_LATENCY=80 +mm_LLC_LATENCY=1 +mm_LLC_WAY_BITS=2 +mm_LLC_SET_BITS=12 +mm_LLC_BLOCK_BITS=6",
  snapshot = true
)
{
  runSuite("vcs", true)(benchmarks)
}*/
