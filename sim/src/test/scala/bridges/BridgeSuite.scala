//See LICENSE for license details.

package firesim.bridges

import java.io._

import org.scalatest.Suites
import org.scalatest.matchers.should._

import freechips.rocketchip.config.Parameters
import freechips.rocketchip.config._

abstract class BridgeSuite(
  val targetName:      String,
  val targetConfigs:   String = "NoConfig",
  val platformConfigs: String = "HostDebugFeatures_DefaultF1Config",
) extends firesim.TestSuiteCommon
    with Matchers {

  val targetTuple = s"$targetName-$targetConfigs-$platformConfigs"

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
    * @param backend
    *   Backend simulator: "verilator" or "vcs"
    * @param debug
    *   When true, captures waves from the simulation
    * @param shouldPass
    *   When false, asserts the test returns a non-zero code
    */
  def runTest(backend: String, debug: Boolean = false, shouldPass: Boolean = true) {
    val prefix     = if (shouldPass) "pass in " else "fail in "
    val testEnvStr = s"${backend} MIDAS-level simulation"
    val wavesStr   = if (debug) " with waves enabled" else ""

    val haveThisBehavior = prefix + testEnvStr + wavesStr

    if (isCmdAvailable(backend)) {
      it should haveThisBehavior in {
        assert((run(backend, debug) == 0) == shouldPass)
      }
    } else {
      ignore should haveThisBehavior in {}
    }
  }

  mkdirs()
  behavior.of(s"$targetName")
  elaborateAndCompile()
  for (backend <- Seq("vcs", "verilator")) {
    compileMlSimulator(backend)
    runTest(backend)
  }
}

class UARTTest(targetConfig: String) extends BridgeSuite("UARTModule", "UARTConfig", targetConfig) {}

class UARTF1Test    extends BridgeSuite("UARTModule", "UARTConfig", "DefaultF1Config")
class UARTVitisTest extends BridgeSuite("UARTModule", "UARTConfig", "DefaultVitisConfig")

class BlockDevTest(targetConfig: String) extends BridgeSuite("BlockDevModule", "BlockDevConfig", targetConfig) {
  override def runTest(backend: String, debug: Boolean, shouldPass: Boolean) {
    val prefix     = if (shouldPass) "pass in " else "fail in "
    val testEnvStr = s"${backend} MIDAS-level simulation"
    val wavesStr   = if (debug) " with waves enabled" else ""

    val haveThisBehavior = prefix + testEnvStr + wavesStr

    if (isCmdAvailable(backend)) {
      // Generate a random string spanning 2 sectors with a fixed seed.
      val gen = new scala.util.Random(100)

      val alpha = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
      val data  = (1 to 1024).map(_ => alpha(gen.nextInt(alpha.length))).mkString

      // Create an input file.
      val input       = File.createTempFile("input", ".txt")
      input.deleteOnExit()
      val inputWriter = new BufferedWriter(new FileWriter(input))
      inputWriter.write(data)
      inputWriter.flush()
      inputWriter.close()

      // Pre-allocate space in the output.
      val output       = File.createTempFile("output", ".txt")
      output.deleteOnExit()
      val outputWriter = new BufferedWriter(new FileWriter(output))
      for (i <- 1 to data.size) {
        outputWriter.write('x')
      }
      outputWriter.flush()
      outputWriter.close()

      it should haveThisBehavior in {
        val runResult = run(backend, debug, args = Seq(s"+blkdev0=${input.getPath}", s"+blkdev1=${output.getPath}"))
        assert((runResult == 0) == shouldPass)
        val result    = scala.io.Source.fromFile(output.getPath).mkString
        result should equal(data)
      }
    } else {
      ignore should haveThisBehavior in {}
    }
  }
}

class BlockDevF1Test    extends BlockDevTest("DefaultF1Config")
class BlockDevVitisTest extends BlockDevTest("DefaultVitisConfig")

class BridgeTests
    extends Suites(
      new UARTF1Test,
      new UARTVitisTest,
      new BlockDevF1Test,
      new BlockDevVitisTest,
    )
