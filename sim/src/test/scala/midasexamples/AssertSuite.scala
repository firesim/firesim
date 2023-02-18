// See LICENSE for license details.

package firesim.midasexamples

import java.io.File
import org.scalatest.Suites
import firesim.TestSuiteUtil._

class AssertModuleF1Test extends TutorialSuite("AssertModule")

class MulticlockAssertF1Test extends TutorialSuite("MulticlockAssertModule")

class AssertTortureTest extends TutorialSuite("AssertTorture") with AssertTortureConstants {
  override def defineTests(backend: String, debug: Boolean) {
    it should "run in the simulator" in {
      assert(run(backend, debug, args = simulationArgs) == 0)
    }
    for (clockIdx <- 0 until 4) {
      it should s"capture asserts in the same order as the reference printfs in clock domain $clockIdx" in {
        val logFile  = new File(outDir, s"/${targetName}.${backend}.out")
        // Diff parts of the simulation's stdout against itself, as the synthesized
        // assertion messages are dumped to the same file as printfs in the RTL
        val expected = extractLines(logFile, prefix = s"${printfPrefix}${clockPrefix(clockIdx)}")
        val actual   = extractLines(logFile, prefix = s"Assertion failed: ${clockPrefix(clockIdx)}")
        diffLines(expected, actual)
      }
    }
  }
}

class AssertGlobalResetConditionF1Test extends TutorialSuite("AssertGlobalResetCondition")

class AssertionSynthesisCITests
    extends Suites(
      new AssertModuleF1Test,
      new MulticlockAssertF1Test,
      new AssertTortureTest,
      new AssertGlobalResetConditionF1Test,
    )
