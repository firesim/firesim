// See LICENSE for license details.

package firesim.midasexamples

import org.scalatest.Suites

class WireInterconnectF1Test  extends TutorialSuite("WireInterconnect")
class TrivialMulticlockF1Test extends TutorialSuite("TrivialMulticlock") {
  runTest("verilator", true)
  runTest("vcs", true)
}

class TriggerWiringModuleF1Test extends TutorialSuite("TriggerWiringModule")

// Basic test for deduplicated extracted models
class TwoAddersF1Test extends TutorialSuite("TwoAdders")

class RegfileF1Test extends TutorialSuite("Regfile")

class MultiRegfileF1Test extends TutorialSuite("MultiRegfile")

class MultiSRAMF1Test extends TutorialSuite("MultiSRAM")

class NestedModelsF1Test extends TutorialSuite("NestedModels")

class MultiRegF1Test extends TutorialSuite("MultiReg")

class GoldenGateMiscCITests
    extends Suites(
      new TwoAddersF1Test,
      new TriggerWiringModuleF1Test,
      new WireInterconnectF1Test,
      new TrivialMulticlockF1Test,
      new RegfileF1Test,
      new MultiRegfileF1Test,
      new MultiSRAMF1Test,
      new NestedModelsF1Test,
      new MultiRegF1Test,
    )
