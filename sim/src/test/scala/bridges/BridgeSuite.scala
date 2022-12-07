//See LICENSE for license details.

package firesim.bridges

import java.io.File

import org.scalatest.Suites
import org.scalatest.matchers.should._

import freechips.rocketchip.config.Parameters
import freechips.rocketchip.config._

abstract class BridgeSuite(
  val targetName:  String, // See GeneratorUtils
  targetConfigs:   String      = "NoConfig",
  platformConfigs: String      = "HostDebugFeatures_DefaultF1Config",
  tracelen:        Int         = 8,
  simulationArgs:  Seq[String] = Seq(),
) extends firesim.TestSuiteCommon
    with Matchers {

  val backendSimulator = "verilator"

  val targetTuple    = s"$targetName-$targetConfigs-$platformConfigs"
  val commonMakeArgs = Seq(
    s"TARGET_PROJECT=bridges",
    s"DESIGN=$targetName",
    s"TARGET_CONFIG=${targetConfigs}",
    s"PLATFORM_CONFIG=${platformConfigs}",
  )

  def run(
    backend:  String,
    debug:    Boolean      = false,
    logFile:  Option[File] = None,
    waveform: Option[File] = None,
    args:     Seq[String]  = Nil,
  ) = {
    val makeArgs = Seq(
      s"run-$backend%s".format(if (debug) "-debug" else ""),
      "LOGFILE=%s".format(logFile.map(toStr).getOrElse("")),
      "WAVEFORM=%s".format(waveform.map(toStr).getOrElse("")),
      "ARGS=%s".format(args.mkString(" ")),
    )
    if (isCmdAvailable(backend)) {
      make(makeArgs: _*)
    } else 0
  }

  /** Runs MIDAS-level simulation on the design.
    *
    * @param b
    *   Backend simulator: "verilator" or "vcs"
    * @param debug
    *   When true, captures waves from the simulation
    * @param args
    *   A seq of PlusArgs to pass to the simulator.
    * @param shouldPass
    *   When false, asserts the test returns a non-zero code
    */
  def runTest(b: String, debug: Boolean = false, args: Seq[String] = simulationArgs, shouldPass: Boolean = true) {
    val prefix     = if (shouldPass) "pass in " else "fail in "
    val testEnvStr = s"${b} MIDAS-level simulation"
    val wavesStr   = if (debug) " with waves enabled" else ""
    val argStr     = " with args: " + args.mkString(" ")

    val haveThisBehavior = prefix + testEnvStr + wavesStr + argStr

    if (isCmdAvailable(b)) {
      it should haveThisBehavior in {
        assert((run(b, debug, args = args) == 0) == shouldPass)
      }
    } else {
      ignore should haveThisBehavior in {}
    }
  }

  mkdirs()
  behavior.of(s"$targetName")
  elaborateAndCompile()
  compileMlSimulator(backendSimulator)
  runTest(backendSimulator)
}

class UARTF1Test    extends BridgeSuite("UARTModule", "UARTConfig", "DefaultF1Config")
class UARTVitisTest extends BridgeSuite("UARTModule", "UARTConfig", "DefaultVitisConfig")

class BridgeTests
    extends Suites(
      new UARTF1Test,
      new UARTVitisTest,
    )
