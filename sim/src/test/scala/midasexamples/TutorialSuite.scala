//See LICENSE for license details.

package firesim.midasexamples

import java.io.File
import scala.util.matching.Regex
import scala.io.Source
import org.scalatest.Suites
import org.scalatest.matchers.should._

import freechips.rocketchip.config.Config
import firesim.{TestSuiteCommon, BasePlatformConfig}

object BaseConfigs {
  case object F1 extends BasePlatformConfig("f1", Seq(classOf[DefaultF1Config]))
  case object Vitis extends BasePlatformConfig("vitis", Seq(classOf[DefaultVitisConfig]))
}

abstract class TutorialSuite(
    val targetName: String,
    override val targetConfigs: String = "NoConfig",
    override val platformConfigs: Seq[Class[_ <: Config]] = Seq(),
    override val basePlatformConfig: BasePlatformConfig = BaseConfigs.F1,
    val simulationArgs: Seq[String] = Seq()
  ) extends TestSuiteCommon("midasexamples") with Matchers {

  val backendSimulator = "verilator"

  def run(backend: String,
          debug: Boolean = false,
          logFile: Option[File] = None,
          waveform: Option[File] = None,
          args: Seq[String] = Nil) = {
    val makeArgs = Seq(
      s"run-$backend%s".format(if (debug) "-debug" else ""),
      "LOGFILE=%s".format(logFile map toStr getOrElse ""),
      "WAVEFORM=%s".format(waveform map toStr getOrElse ""),
      "ARGS=%s".format(args mkString " "))
    if (isCmdAvailable(backend)) {
      make(makeArgs:_*)
    } else 0
  }


  /**
    * Runs MIDAS-level simulation on the design.
    *
    * @param b Backend simulator: "verilator" or "vcs"
    * @param debug When true, captures waves from the simulation
    * @param args A seq of PlusArgs to pass to the simulator.
    * @param shouldPass When false, asserts the test returns a non-zero code
    */
  def runTest(b: String, debug: Boolean = false, args: Seq[String] = simulationArgs, shouldPass: Boolean = true) {
    val prefix =  if (shouldPass) "pass in " else "fail in "
    val testEnvStr  = s"${b} MIDAS-level simulation"
    val wavesStr = if (debug) " with waves enabled" else ""
    val argStr = " with args: " + args.mkString(" ")

    val haveThisBehavior = prefix + testEnvStr + wavesStr + argStr

    if (isCmdAvailable(b)) {
      it should haveThisBehavior in {
         assert((run(b, debug, args = args) == 0) == shouldPass)
      }
    } else {
      ignore should haveThisBehavior in { }
    }
  }

  mkdirs()
  behavior of s"$targetName"
  elaborateAndCompile()
  compileMlSimulator(backendSimulator)
  runTest(backendSimulator)
}

class VitisCITests extends Suites (
  new GCDVitisTest,
  new ParityVitisTest,
  new PrintfModuleVitisTest,
  new MulticlockPrintVitisTest,
)

// These groups are vestigial from CircleCI container limits
class CIGroupA extends Suites(
  new ChiselExampleDesigns,
  new PrintfSynthesisCITests,
  new firesim.fasedtests.CIGroupA,
  new AutoCounterCITests,
  new ResetPulseBridgeActiveHighTest,
  new ResetPulseBridgeActiveLowTest,
)

class CIGroupB extends Suites(
  new AssertionSynthesisCITests,
  new GoldenGateMiscCITests,
  new firesim.fasedtests.CIGroupB,
  new firesim.AllMidasUnitTests,
  new firesim.FailingUnitTests,
  new FMRCITests,
  new VitisCITests
)
