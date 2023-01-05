// See LICENSE for license details.

package firesim.midasexamples

import java.io.File
import org.scalatest.Suites
import firesim.TestSuiteUtil._

class AssertModuleF1Test extends TutorialSuite("AssertModule")

class MulticlockAssertF1Test extends TutorialSuite("MulticlockAssertModule")

class AssertTortureTest extends TutorialSuite("AssertTorture") with AssertTortureConstants {
  def checkClockDomainAssertionOrder(clockIdx: Int): Unit = {
    it should s"capture asserts in the same order as the reference printfs in clock domain $clockIdx" in {
      val verilatedLogFile = new File(outDir, s"/${targetName}.verilator.out")
      // Diff parts of the simulation's stdout against itself, as the synthesized
      // assertion messages are dumped to the same file as printfs in the RTL
      val expected         = extractLines(verilatedLogFile, prefix = s"${printfPrefix}${clockPrefix(clockIdx)}")
      val actual           = extractLines(verilatedLogFile, prefix = s"Assertion failed: ${clockPrefix(clockIdx)}")
      diffLines(expected, actual)
    }
  }
  // TODO: Create a target-parameters instance we can inspect here
  Seq.tabulate(4)(i => checkClockDomainAssertionOrder(i))
}

class AssertGlobalResetConditionF1Test extends TutorialSuite("AssertGlobalResetCondition")

class AssertionSynthesisCITests
    extends Suites(
      new AssertModuleF1Test,
      new MulticlockAssertF1Test,
      new AssertTortureTest,
      new AssertGlobalResetConditionF1Test,
    )
