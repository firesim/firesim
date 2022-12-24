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
    */
  def runTest(backend: String, debug: Boolean = false)

  /** Helper to generate tests strings.
    */
  def getTestString(length: Int): String = {
    val gen   = new scala.util.Random(100)
    val alpha = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    (1 to length).map(_ => alpha(gen.nextInt(alpha.length))).mkString
  }

  mkdirs()
  behavior.of(s"$targetName")
  elaborateAndCompile()
  for (backend <- Seq("vcs", "verilator")) {
    compileMlSimulator(backend)

    val testEnvStr = s"pass in ${backend} MIDAS-level simulation"

    if (isCmdAvailable(backend)) {
      it should testEnvStr in {
        runTest(backend)
      }
    } else {
      ignore should testEnvStr in {}
    }
  }
}

class UARTTest(targetConfig: String) extends BridgeSuite("UARTModule", "UARTConfig", targetConfig) {
  override def runTest(backend: String, debug: Boolean) {
    // Generate a short test string.
    val data = getTestString(16)

    // Create an input file.
    val input       = File.createTempFile("input", ".txt")
    input.deleteOnExit()
    val inputWriter = new BufferedWriter(new FileWriter(input))
    inputWriter.write(data)
    inputWriter.flush()
    inputWriter.close()

    // Create an output file to write to.
    val output = File.createTempFile("output", ".txt")

    val runResult = run(backend, debug, args = Seq(s"+uart-in0=${input.getPath}", s"+uart-out0=${output.getPath}"))
    assert(runResult == 0)
    val result    = scala.io.Source.fromFile(output.getPath).mkString
    result should equal(data)
  }
}

class UARTF1Test    extends UARTTest("DefaultF1Config")
class UARTVitisTest extends UARTTest("DefaultVitisConfig")

class BlockDevTest(targetConfig: String) extends BridgeSuite("BlockDevModule", "BlockDevConfig", targetConfig) {
  override def runTest(backend: String, debug: Boolean) {
    // Generate a random string spanning 2 sectors with a fixed seed.
    val data = getTestString(1024)

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

    val runResult = run(backend, debug, args = Seq(s"+blkdev0=${input.getPath}", s"+blkdev1=${output.getPath}"))
    assert(runResult == 0)
    val result    = scala.io.Source.fromFile(output.getPath).mkString
    result should equal(data)
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
