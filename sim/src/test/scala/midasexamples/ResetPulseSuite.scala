//See LICENSE for license details.

package firesim.midasexamples

import org.scalatest.Suites

class ResetPulseBridgeActiveHighTest
    extends TutorialSuite(
      "ResetPulseBridgeTest",
      // Disable assertion synthesis to rely on native chisel assertions to catch bad behavior
      platformConfigs = Seq(classOf[NoSynthAsserts]),
      simulationArgs  = Seq(s"+reset-pulse-length0=${ResetPulseBridgeTestConsts.maxPulseLength}"),
    )

class ResetPulseBridgeActiveHighFailTest
    extends TutorialSuite(
      "ResetPulseBridgeTest",
      // Disable assertion synthesis to rely on native chisel assertions to catch bad behavior
      platformConfigs = Seq(classOf[NoSynthAsserts]),
      simulationArgs  = Seq(s"+reset-pulse-length0=${ResetPulseBridgeTestConsts.maxPulseLength + 1}"),
      shouldPass      = false,
    )

class ResetPulseBridgeActiveLowTest
    extends TutorialSuite(
      "ResetPulseBridgeTest",
      targetConfigs   = "ResetPulseBridgeActiveLowConfig",
      platformConfigs = Seq(classOf[NoSynthAsserts]),
      simulationArgs  = Seq(s"+reset-pulse-length0=${ResetPulseBridgeTestConsts.maxPulseLength}"),
    )

class ResetPulseBridgeActiveLowFailTest
    extends TutorialSuite(
      "ResetPulseBridgeTest",
      targetConfigs   = "ResetPulseBridgeActiveLowConfig",
      platformConfigs = Seq(classOf[NoSynthAsserts]),
      simulationArgs  = Seq(s"+reset-pulse-length0=${ResetPulseBridgeTestConsts.maxPulseLength + 1}"),
      shouldPass      = false,
    )

class ResetPulseBridgeTests
    extends Suites(
      new ResetPulseBridgeActiveHighTest,
      new ResetPulseBridgeActiveHighFailTest,
      new ResetPulseBridgeActiveLowTest,
      new ResetPulseBridgeActiveLowFailTest,
    )
