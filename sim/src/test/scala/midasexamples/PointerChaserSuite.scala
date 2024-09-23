//See LICENSE for license details.

package firesim.midasexamples

import org.scalatest.Suites

class PointerChaserTest extends TutorialSuite("PointerChaser", targetConfigs = "PointerChaserConfig")
class PointerChaserLPCTest
    extends TutorialSuite(
      "PointerChaser",
      targetConfigs   = "PointerChaserConfig",
      platformConfigs = Seq("PointerChaserLPC"),
    )

class PointerChaserTests
    extends Suites(
      new PointerChaserTest,
      new PointerChaserLPCTest,
    )
