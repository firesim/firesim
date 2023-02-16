//See LICENSE for license details.

package firesim.midasexamples

import java.io._

import org.scalatest.Suites
import org.scalatest.matchers.should._

import freechips.rocketchip.config.Config
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.config._

import firesim.{BasePlatformConfig, TestSuiteCommon}

abstract class LoadMemTest(
  override val basePlatformConfig: BasePlatformConfig
) extends TestSuiteCommon("midasexamples")
    with Matchers {

  override def targetConfigs   = "NoConfig"
  override def targetName      = "LoadMemModule"
  override def platformConfigs = Seq(classOf[NoSynthAsserts])

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

  /** Helper to generate tests strings.
    */
  def getTestInput(length: Int): String = {
    val gen   = new scala.util.Random(100)
    val alpha = "0123456789abcdef"
    Seq
      .tabulate(length) { _ =>
        (1 to 16).map(_ => alpha(gen.nextInt(alpha.length))).mkString
      }
      .mkString("\n")
  }

  /** Runs MIDAS-level simulation on the design.
    *
    * @param backend
    *   Backend simulator: "verilator" or "vcs"
    * @param debug
    *   When true, captures waves from the simulation
    */
  def runTest(backend: String, debug: Boolean) {
    // Generate a random string spanning 2 sectors with a fixed seed.
    val numLines = 128
    val data     = getTestInput(numLines)

    // Create an input file.
    val input       = File.createTempFile("input", ".txt")
    //input.deleteOnExit()
    val inputWriter = new BufferedWriter(new FileWriter(input))
    inputWriter.write(data)
    inputWriter.flush()
    inputWriter.close()

    // Pre-allocate space in the output.
    val output       = File.createTempFile("output", ".txt")
    //output.deleteOnExit()
    val outputWriter = new BufferedWriter(new FileWriter(output))
    for (i <- 1 to data.size) {
      outputWriter.write('x')
    }
    outputWriter.flush()
    outputWriter.close()

    val runResult =
      run(backend, debug, args = Seq(s"+n=${numLines} +loadmem=${input.getPath}", s"+test-dump-file=${output.getPath}"))
    assert(runResult == 0)
    val result    = scala.io.Source.fromFile(output.getPath).mkString
    result should equal(data + "\n")
  }

  mkdirs()
  behavior.of(s"$targetName")
  elaborateAndCompile()
  for (backend <- Seq("vcs", "verilator")) {
    compileMlSimulator(backend)

    val testEnvStr = s"pass in ${backend} MIDAS-level simulation"

    if (isCmdAvailable(backend)) {
      it should testEnvStr in {
        runTest(backend, false)
      }
    } else {
      ignore should testEnvStr in {}
    }
  }
}

class LoadMemF1Test    extends LoadMemTest(BaseConfigs.F1)
class LoadMemVitisTest extends LoadMemTest(BaseConfigs.Vitis)

class BridgeTests
    extends Suites(
      new LoadMemF1Test,
      new LoadMemVitisTest,
    )
