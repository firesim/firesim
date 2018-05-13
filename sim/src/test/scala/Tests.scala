package firesim

import chisel3.internal.firrtl.Port
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.devices.debug.DebugIO
import freechips.rocketchip.system.{RocketTestSuite, BenchmarkTestSuite}
import freechips.rocketchip.system.TestGeneration._
import freechips.rocketchip.system.DefaultTestSuites._
import freechips.rocketchip.config.{Config, Parameters}
import scala.concurrent.{Future, Await, ExecutionContext}
import scala.sys.process.{stringSeqToProcess, ProcessLogger}
import scala.reflect.ClassTag
import java.io.{File, FileWriter}

abstract class FireSimTestSuite(
    targetName: String,
    targetConfig: Config,
    platformConfig: Config,
    simulationArgs: String,
    snapshot: Boolean = false,
    hammer: Boolean = false,
    lib: Option[File] = None,
    N: Int = 5) extends org.scalatest.FlatSpec with HasTestSuites {
  import scala.concurrent.duration._
  import ExecutionContext.Implicits.global

  val platformParams = Parameters.root(platformConfig.toInstance) alterPartial Map(midas.EnableSnapshot -> snapshot)
  val platformConfigName = platformConfig.getClass.getSimpleName
  val platformName = platformParams(midas.Platform).toString.toLowerCase
  val targetParams = Parameters.root(targetConfig.toInstance)
  val targetConfigName = targetConfig.getClass.getSimpleName

  val genDir = new File(new File("generated-src", platformName), targetConfigName) ; genDir.mkdirs
  val outDir = new File(new File("output", platformName), targetConfigName) ; outDir.mkdirs

  val replayBackends = "rtl" +: (if (hammer) Seq("syn") else Seq())

  implicit val valName = ValName(targetName)
  lazy val target = targetName match {
    case "FireSim"  => LazyModule(new FireSim()(targetParams)).module
    case "FireBoom" => LazyModule(new FireBoom()(targetParams)).module
  }
  val circuit = chisel3.Driver.elaborate(() => target)
  val chirrtl = firrtl.Parser.parse(chisel3.Driver.emit(circuit))
  val annos = circuit.annotations
  val ports = target.getPorts flatMap {
    case Port(id: DebugIO, _) => None
    case Port(id: AutoBundle, _) => None // What the hell is AutoBundle?
    case otherPort => Some(otherPort.id)
  }
  midas.MidasCompiler(chirrtl, annos, ports, genDir, lib)(platformParams)
  if (platformParams(midas.EnableSnapshot))
    strober.replay.Compiler(chirrtl, annos, ports, genDir, lib)
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

  def isCmdAvailable(cmd: String) =
    Seq("which", cmd) ! ProcessLogger(_ => {}) == 0

  def runTest(backend: String, name: String, debug: Boolean) = {
    val dir = (new File(outDir, backend)).getAbsolutePath
    (Seq("make", s"${dir}/${name}.%s".format(if (debug) "vpd" else "out"),
         s"EMUL=$backend", s"output_dir=$dir") ++ makeArgs).!
  }

  def runReplay(backend: String, replayBackend: String, name: String) = {
    val dir = (new File(outDir, backend)).getAbsolutePath
    (Seq("make", s"replay-$replayBackend",
         s"SAMPLE=${dir}/${name}.sample", s"output_dir=$dir") ++ makeArgs).!
  }

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
      replayBackends foreach { replayBackend =>
        if (platformParams(midas.EnableSnapshot) && isCmdAvailable("vcs")) {
          assert((Seq("make", s"vcs-$replayBackend") ++ makeArgs).! == 0) // compile vcs
          suite.names foreach { name =>
            it should s"replay $name in $replayBackend" in {
              assert(runReplay(backend, replayBackend, s"$name$postfix") == 0)
            }
          }
        } else {
          suite.names foreach { name =>
            ignore should s"replay $name in $backend"
          }
        }
      }
    } else {
      ignore should s"pass $backend"
    }
  }
}

class RocketChipF1Tests extends FireSimTestSuite(
  "FireSim",
  new RocketChipConfig,
  new FireSimConfig,
  "+dramsim +mm_MEM_LATENCY=80 +mm_LLC_LATENCY=1 +mm_LLC_WAY_BITS=2 +mm_LLC_SET_BITS=12 +mm_LLC_BLOCK_BITS=6",
  snapshot = true
  //, hammer = true
  //, lib = Some(new File("hammer/obj/technology/saed32/hammer-generated/all.macro_library.json"))
)
{
  runSuite("verilator")(benchmarks)
  runSuite("vcs", true)(benchmarks)
}

class BoomF1Tests extends FireSimTestSuite(
  "FireBoom",
  new BoomConfig,
  new FireSimConfig,
  "+dramsim +mm_MEM_LATENCY=80 +mm_LLC_LATENCY=1 +mm_LLC_WAY_BITS=2 +mm_LLC_SET_BITS=12 +mm_LLC_BLOCK_BITS=6",
  snapshot = true
  //, hammer = true
  //, lib = Some(new File("hammer/obj/technology/saed32/hammer-generated/all.macro_library.json"))
)
{
  runSuite("vcs", true)(benchmarks)
}
