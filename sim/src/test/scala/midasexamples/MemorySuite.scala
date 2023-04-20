//See LICENSE for license details.

package firesim.midasexamples

import java.io._

import org.scalatest.Suites
import org.scalatest.matchers.should._

import org.chipsalliance.cde.config.Config
import org.chipsalliance.cde.config.Parameters
import org.chipsalliance.cde.config._

import firesim.{BasePlatformConfig, TestSuiteCommon}

abstract class LoadMemTest(
  override val basePlatformConfig: BasePlatformConfig,
  val extraArgs:                   Seq[String] = Seq(),
) extends TutorialSuite("LoadMemModule", platformConfigs = Seq(classOf[NoSynthAsserts]))
    with Matchers {

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
  override def defineTests(backend: String, debug: Boolean) {
    it should "read data provided by LoadMem" in {
      // Generate a random string spanning 2 sectors with a fixed seed.
      val numLines = 128
      val data     = getTestInput(numLines)

      // Create an input file.
      val input       = File.createTempFile("input", ".txt")
      input.deleteOnExit()
      val inputWriter = new BufferedWriter(new FileWriter(input))
      inputWriter.write(data)
      inputWriter.flush()
      inputWriter.close()

      // Create an output file.
      val output = File.createTempFile("output", ".txt")

      // Run the test and compare the two files.
      assert(
        run(
          backend,
          debug,
          args = Seq(s"+n=${numLines} +loadmem=${input.getPath}", s"+test-dump-file=${output.getPath}") ++ extraArgs,
        ) == 0
      )
      val result = scala.io.Source.fromFile(output.getPath).mkString
      result should equal(data + "\n")
    }
  }
}

class LoadMemF1Test    extends LoadMemTest(BaseConfigs.F1)
class LoadMemVitisTest extends LoadMemTest(BaseConfigs.Vitis)

class FastLoadMemF1Test    extends LoadMemTest(BaseConfigs.F1, extraArgs = Seq("+fastloadmem"))
class FastLoadMemVitisTest extends LoadMemTest(BaseConfigs.Vitis, extraArgs = Seq("+fastloadmem"))

class MemoryCITests
    extends Suites(
      new LoadMemF1Test,
      new LoadMemVitisTest,
      new FastLoadMemF1Test,
      new FastLoadMemVitisTest,
    )
