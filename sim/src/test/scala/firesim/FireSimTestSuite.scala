// See LICENSE for license details.

package firesim.firesim

import java.io.File

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.sys.process.{stringSeqToProcess, ProcessLogger}
import scala.io.Source
import org.scalatest.Suites

import firesim.configs._
import firesim.{BasePlatformConfig, TestSuiteCommon}

import freechips.rocketchip.config.Config
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.system.{BenchmarkTestSuite, RocketTestSuite}
import freechips.rocketchip.system.TestGeneration._
import freechips.rocketchip.system.DefaultTestSuites._

object BaseConfigs {
  case object F1    extends BasePlatformConfig("f1", Seq(classOf[BaseF1Config]))
  case object Vitis extends BasePlatformConfig("vitis", Seq(classOf[BaseVitisConfig]))
}

abstract class FireSimTestSuite(
  override val targetName:         String,
  override val targetConfigs:      String,
  override val basePlatformConfig: BasePlatformConfig,
  override val platformConfigs:    Seq[Class[_ <: Config]] = Seq(),
  N:                               Int                     = 8,
) extends TestSuiteCommon("firesim") {

  import scala.concurrent.duration._
  import ExecutionContext.Implicits.global

  val topModuleProject = "firesim.firesim"

  val chipyardLongName = topModuleProject + "." + targetName + "." + targetConfigs

  override lazy val genDir = new File(firesimDir, s"generated-src/${chipyardLongName}")

  def invokeMlSimulator(backend: String, name: String, debug: Boolean, additionalArgs: Seq[String] = Nil) = {
    make(
      (Seq(s"${outDir.getAbsolutePath}/${name}.%s".format(if (debug) "vpd" else "out"), s"EMUL=${backend}")
        ++ additionalArgs): _*
    )
  }

  def runTest(backend: String, name: String, debug: Boolean, additionalArgs: Seq[String] = Nil) = {
    compileMlSimulator(backend, debug)
    if (isCmdAvailable(backend)) {
      it should s"pass in ML simulation on ${backend}" in {
        assert(invokeMlSimulator(backend, name, debug, additionalArgs) == 0)
      }
    }
  }

  def runSuite(backend: String, debug: Boolean = false)(suite: RocketTestSuite) {
    // compile emulators
    behavior.of(s"${suite.makeTargetName} running on $backend")
    if (isCmdAvailable(backend)) {
      val postfix = suite match {
        case _: BenchmarkTestSuite | _: BlockdevTestSuite | _: NICTestSuite => ".riscv"
        case _                                                              => ""
      }
      it should s"pass all tests in ${suite.makeTargetName}" in {
        val results = suite.names.toSeq.sliding(N, N).map { t =>
          val subresults = t.map(name => Future(name -> invokeMlSimulator(backend, s"$name$postfix", debug)))
          Await.result(Future.sequence(subresults), Duration.Inf)
        }
        results.flatten.foreach { case (name, exitcode) =>
          assert(exitcode == 0, s"Failed $name")
        }
      }
    } else {
      ignore should s"pass $backend"
    }
  }

  // Checks the collected trace log matches the behavior of a chisel printf
  def diffTracelog(verilatedLog: String) {
    behavior.of("captured instruction trace")
    it should s"match the chisel printf in ${verilatedLog}" in {
      def getLines(file: File): Seq[String] = Source.fromFile(file).getLines.toList

      val printfPrefix    = "TRACEPORT 0: "
      val verilatedOutput = getLines(new File(outDir, s"/${verilatedLog}")).collect({
        case line if line.startsWith(printfPrefix) => line.stripPrefix(printfPrefix)
      })

      // Last bit indicates the core was under reset; reject those tokens
      // Tail to drop the first token which is initialized in the channel
      val synthPrintOutput = getLines(new File(genDir, s"/TRACEFILE")).tail.filter(line => (line.last.toInt & 1) == 0)

      assert(
        math.abs(verilatedOutput.size - synthPrintOutput.size) <= 1,
        s"\nPrintf Length: ${verilatedOutput.size}, Trace Length: ${synthPrintOutput.size}",
      )
      assert(verilatedOutput.nonEmpty)
      for ((vPrint, sPrint) <- verilatedOutput.zip(synthPrintOutput)) {
        assert(vPrint == sPrint)
      }
    }
  }

  mkdirs
  behavior.of(s"Tuple: ${targetTuple}")
  elaborateAndCompile()
  runTest("verilator", "rv64ui-p-simple", false, Seq(s"""EXTRA_SIM_ARGS=+trace-humanreadable0"""))
  runSuite("verilator")(benchmarks)
}

class SimpleRocketF1Tests
    extends FireSimTestSuite(
      "FireSim",
      "DDR3FCFS_FireSimRocketConfig",
      BaseConfigs.F1,
    )

class RocketF1Tests
    extends FireSimTestSuite(
      "FireSim",
      "DDR3FRFCFSLLC4MB_FireSimQuadRocketConfig",
      BaseConfigs.F1,
      Seq(classOf[WithSynthAsserts]),
    )

class MultiRocketF1Tests
    extends FireSimTestSuite(
      "FireSim",
      "DDR3FRFCFSLLC4MB_FireSimQuadRocketConfig",
      BaseConfigs.F1,
      Seq(classOf[WithSynthAsserts], classOf[WithModelMultiThreading]),
    )

class BoomF1Tests
    extends FireSimTestSuite(
      "FireSim",
      "DDR3FRFCFSLLC4MB_FireSimLargeBoomConfig",
      BaseConfigs.F1,
    )

class RocketNICF1Tests
    extends FireSimTestSuite(
      "FireSim",
      "WithNIC_DDR3FRFCFSLLC4MB_FireSimRocketConfig",
      BaseConfigs.F1,
    )

class RocketVitisTests
    extends FireSimTestSuite(
      "FireSim",
      "DDR3FRFCFSLLC4MB_FireSimQuadRocketConfig",
      BaseConfigs.Vitis,
      Seq(classOf[WithSynthAsserts]),
    )

class MultiRocketVitisTests
    extends FireSimTestSuite(
      "FireSim",
      "DDR3FRFCFSLLC4MB_FireSimQuadRocketConfig",
      BaseConfigs.Vitis,
      Seq(classOf[WithSynthAsserts], classOf[WithModelMultiThreading]),
    )

class BoomVitisTests
    extends FireSimTestSuite(
      "FireSim",
      "DDR3FRFCFSLLC4MB_FireSimLargeBoomConfig",
      BaseConfigs.Vitis,
    )

class CVA6F1Tests
    extends FireSimTestSuite(
      "FireSim",
      "WithNIC_DDR3FRFCFSLLC4MB_FireSimCVA6Config",
      BaseConfigs.F1,
    )

// This test suite only mirrors what is run in CI. CI invokes each test individually, using a testOnly call.
class CITests
    extends Suites(
      new SimpleRocketF1Tests,
      new RocketF1Tests,
      new MultiRocketF1Tests,
      new BoomF1Tests,
      new RocketNICF1Tests,
    )
