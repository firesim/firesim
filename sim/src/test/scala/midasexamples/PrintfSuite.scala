//See LICENSE for license details.

package firesim.midasexamples

import java.io.File
import org.chipsalliance.cde.config.Config
import org.scalatest.Suites

import firesim.TestSuiteUtil._
import firesim.BasePlatformConfig

abstract class PrintfSuite(
  targetName:         String,
  targetConfigs:      String                  = "NoConfig",
  platformConfigs:    Seq[Class[_ <: Config]] = Seq(),
  basePlatformConfig: BasePlatformConfig      = BaseConfigs.F1,
  simulationArgs:     Seq[String]             = Seq(),
) extends TutorialSuite(targetName, targetConfigs, platformConfigs, basePlatformConfig, simulationArgs) {

  /** Check that we are extracting from the desired ROI by checking that the bridge-inserted cycle prefix matches the
    * target-side cycle prefix
    */
  def checkPrintCycles(filename: String, startCycle: Int, endCycle: Int, linesPerCycle: Int) {
    val synthLogFile     = new File(genDir, s"/${filename}")
    val synthPrintOutput = extractLines(synthLogFile, prefix = "")
    val length           = synthPrintOutput.size
    assert(length == linesPerCycle * (endCycle - startCycle + 1))
    for ((line, idx) <- synthPrintOutput.zipWithIndex) {
      val currentCycle = idx / linesPerCycle + startCycle
      val printRegex   = raw"^CYCLE:\s*(\d*) SYNTHESIZED_PRINT CYCLE:\s*(\d*).*".r
      line match {
        case printRegex(cycleA, cycleB) =>
          assert(cycleA.toInt == currentCycle)
          assert(cycleB.toInt == currentCycle)
      }
    }
  }

  // Checks that a bridge generated log in ${genDir}/${synthLog} matches output
  // generated directly by the RTL simulator (usually with printfs)
  def diffSynthesizedLog(
    backend:          String,
    synthLog:         String,
    stdoutPrefix:     String = "SYNTHESIZED_PRINT ",
    synthPrefix:      String = "SYNTHESIZED_PRINT ",
    synthLinesToDrop: Int    = 0,
  ) {
    val verilatedLogFile = new File(outDir, s"/${targetName}.${backend}.out")
    val synthLogFile     = new File(genDir, s"/${synthLog}")
    val verilatedOutput  = extractLines(verilatedLogFile, stdoutPrefix).sorted
    val synthPrintOutput = extractLines(synthLogFile, synthPrefix, synthLinesToDrop).sorted
    diffLines(verilatedOutput, synthPrintOutput)
  }

  def addChecks(backend: String): Unit

  override def defineTests(backend: String, debug: Boolean) {
    it should "run in the simulator" in {
      assert(run(backend, debug, args = simulationArgs) == 0)
    }
    it should "should produce the expected output" in {
      addChecks(backend)
    }
  }
}

abstract class PrintModuleTest(val platform: BasePlatformConfig)
    extends PrintfSuite(
      "PrintfModule",
      simulationArgs     = Seq("+print-no-cycle-prefix", "+print-file=synthprinttest.out"),
      basePlatformConfig = platform,
    ) {
  override def addChecks(backend: String) {
    diffSynthesizedLog(backend, "synthprinttest.out0")
  }
}

class PrintfModuleF1Test    extends PrintModuleTest(BaseConfigs.F1)
class PrintfModuleVitisTest extends PrintModuleTest(BaseConfigs.Vitis)

abstract class NarrowPrintfModuleTest(val platform: BasePlatformConfig)
    extends PrintfSuite(
      "NarrowPrintfModule",
      simulationArgs     = Seq("+print-no-cycle-prefix", "+print-file=synthprinttest.out"),
      basePlatformConfig = platform,
    ) {
  override def addChecks(backend: String) {
    diffSynthesizedLog(backend, "synthprinttest.out0")
  }
}

class NarrowPrintfModuleF1Test    extends NarrowPrintfModuleTest(BaseConfigs.F1)
class NarrowPrintfModuleVitisTest extends NarrowPrintfModuleTest(BaseConfigs.Vitis)

abstract class MulticlockPrintfModuleTest(val platform: BasePlatformConfig)
    extends PrintfSuite(
      "MulticlockPrintfModule",
      simulationArgs     = Seq("+print-file=synthprinttest.out", "+print-no-cycle-prefix"),
      basePlatformConfig = platform,
    ) {
  override def addChecks(backend: String) {
    diffSynthesizedLog(backend, "synthprinttest.out0")
    diffSynthesizedLog(
      backend,
      "synthprinttest.out1",
      stdoutPrefix     = "SYNTHESIZED_PRINT_HALFRATE ",
      synthPrefix      = "SYNTHESIZED_PRINT_HALFRATE ",
      // Corresponds to a single cycle of extra output.
      synthLinesToDrop = 4,
    )
  }
}

class MulticlockPrintF1Test    extends MulticlockPrintfModuleTest(BaseConfigs.F1)
class MulticlockPrintVitisTest extends MulticlockPrintfModuleTest(BaseConfigs.Vitis)

class AutoCounterPrintfF1Test
    extends PrintfSuite(
      "AutoCounterPrintfModule",
      simulationArgs  = Seq("+print-file=synthprinttest.out"),
      platformConfigs = Seq(classOf[AutoCounterPrintf]),
    ) {
  override def addChecks(backend: String) {
    diffSynthesizedLog(backend, "synthprinttest.out0", stdoutPrefix = "AUTOCOUNTER_PRINT CYCLE", synthPrefix = "CYCLE")
  }
}

class TriggerPredicatedPrintfF1Test
    extends PrintfSuite("TriggerPredicatedPrintf", simulationArgs = Seq("+print-file=synthprinttest.out")) {
  override def addChecks(backend: String) {
    import TriggerPredicatedPrintfConsts._
    checkPrintCycles("synthprinttest.out0", assertTriggerCycle + 2, deassertTriggerCycle + 2, linesPerCycle = 2)
  }
}

class PrintfCycleBoundsTestBase(startCycle: Int, endCycle: Int)
    extends PrintfSuite(
      "PrintfModule",
      simulationArgs = Seq(
        "+print-file=synthprinttest.out",
        s"+print-start=${startCycle}",
        s"+print-end=${endCycle}",
      ),
    ) {
  override def addChecks(backend: String) {
    checkPrintCycles("synthprinttest.out0", startCycle, endCycle, linesPerCycle = 4)
  }
}

class PrintfCycleBoundsF1Test extends PrintfCycleBoundsTestBase(startCycle = 172, endCycle = 9377)

class PrintfGlobalResetConditionTest
    extends TutorialSuite(
      "PrintfGlobalResetCondition",
      simulationArgs = Seq("+print-no-cycle-prefix", "+print-file=synthprinttest.out"),
    ) {

  def assertSynthesizedLogEmpty(synthLog: String, backend: String) {
    s"${synthLog} for ${backend}" should "be empty" in {
      val synthLogFile = new File(genDir, s"/${synthLog}")
      val lines        = extractLines(synthLogFile, prefix = "")
      assert(lines.isEmpty)
    }
  }

  override def defineTests(backend: String, debug: Boolean) {
    it should s"run in the ${backend} simulator" in {
      assert(run(backend, debug, args = simulationArgs) == 0)
    }
    // The log should be empty.
    assertSynthesizedLogEmpty("synthprinttest.out0", backend)
    assertSynthesizedLogEmpty("synthprinttest.out1", backend)
  }
}

class PrintfSynthesisCITests
    extends Suites(
      new PrintfModuleF1Test,
      new PrintfModuleVitisTest,
      new NarrowPrintfModuleF1Test,
      new MulticlockPrintF1Test,
      new PrintfCycleBoundsF1Test,
      new TriggerPredicatedPrintfF1Test,
      new PrintfGlobalResetConditionTest,
      new AutoCounterPrintfF1Test,
    )
