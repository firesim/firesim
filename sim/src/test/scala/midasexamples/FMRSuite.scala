// See LICENSE for license details.

package firesim.midasexamples

import java.io.File
import org.chipsalliance.cde.config.Config
import scala.io.Source
import org.scalatest.Suites

import firesim.BasePlatformConfig

abstract class FMRSuite(
  targetName:         String,
  expectedValue:      Double,
  error:              Double                  = 0.0,
  targetConfigs:      String                  = "NoConfig",
  platformConfigs:    Seq[Class[_ <: Config]] = Seq(),
  basePlatformConfig: BasePlatformConfig      = BaseConfigs.F1,
  simulationArgs:     Seq[String]             = Seq(),
) extends TutorialSuite(targetName, targetConfigs, platformConfigs, basePlatformConfig, simulationArgs) {
  override def defineTests(backend: String, debug: Boolean) {
    it should "run in the simulator" in {
      assert(run(backend, debug, args = simulationArgs) == 0)
    }

    it should s"run with an FMR between ${expectedValue - error} and ${expectedValue + error}" in {
      val verilatedLogFile = new File(outDir, s"/${targetName}.${backend}.out")
      val lines            = Source.fromFile(verilatedLogFile).getLines.toList.reverse
      val fmrRegex         = raw"^FMR: (\d*\.\d*)".r
      val fmr              = lines.collectFirst { case fmrRegex(value) =>
        value.toDouble
      }
      assert(fmr.nonEmpty, "FMR value not found.")
      assert(fmr.get >= expectedValue - error)
      assert(fmr.get <= expectedValue + error)
    }
  }
}

class MultiRegfileFMRF1Test extends FMRSuite("MultiRegfileFMR", expectedValue = 2.0 * MultiRegfile.nCopiesToTime) {
  // A threaded model that relies on another model to implement an internal
  // combinational path (like an extracted memory model) will only simulate
  // one target thread-cycle every two host cycles.
}

class MultiSRAMFMRF1Test extends FMRSuite("MultiSRAMFMR", expectedValue = MultiRegfile.nCopiesToTime) {
  // No comb paths -> 1:1
}

class PassthroughModelTest extends FMRSuite("PassthroughModel", expectedValue = 2.0)

class PassthroughModelNestedTest extends FMRSuite("PassthroughModelNested", expectedValue = 2.0)

class PassthroughModelBridgeSourceTest extends FMRSuite("PassthroughModelBridgeSource", expectedValue = 1.0)

class FMRCITests
    extends Suites(
      new MultiRegfileFMRF1Test,
      new MultiSRAMFMRF1Test,
      new PassthroughModelTest,
      new PassthroughModelNestedTest,
      new PassthroughModelBridgeSourceTest,
    )
