//See LICENSE for license details.

package firesim.midasexamples

import org.scalatest.Suites
import org.scalatest.matchers.should._

import firesim.{BasePlatformConfig, TestSuiteCommon}

object BaseConfigs {
  case object F1 extends BasePlatformConfig("f1", Seq("DefaultF1Config"))
}

abstract class TutorialSuite(
  val targetName:                  String,
  override val targetConfigs:      String             = "NoConfig",
  override val platformConfigs:    Seq[String]        = Seq(),
  override val basePlatformConfig: BasePlatformConfig = BaseConfigs.F1,
  val simulationArgs:              Seq[String]        = Seq(),
  val shouldPass:                  Boolean            = true,
) extends TestSuiteCommon("midasexamples")
    with Matchers {

  override def defineTests(backend: String, debug: Boolean): Unit = {
    val prefix   = if (shouldPass) "pass in " else "fail "
    val wavesStr = if (debug) " with waves enabled" else ""
    val argStr   = " with args: " + simulationArgs.mkString(" ")

    val haveThisBehavior = prefix + wavesStr + argStr

    it should haveThisBehavior in {
      assert((run(backend, debug, args = simulationArgs) == 0) == shouldPass)
    }
  }
}

// These groups are vestigial from CircleCI container limits
class CIGroupA
    extends Suites(
      new ChiselExampleDesigns,
      new PrintfSynthesisCITests,
      new firesim.fasedtests.CIGroupA,
      new AutoCounterCITests,
      new ResetPulseBridgeActiveHighTest,
      new ResetPulseBridgeActiveLowTest,
      new MemoryCITests,
    )

class CIGroupB
    extends Suites(
      new AssertionSynthesisCITests,
      new GoldenGateMiscCITests,
      new firesim.fasedtests.CIGroupB,
      new firesim.AllMidasUnitTests,
      new firesim.FailingUnitTests,
      new FMRCITests,
      new PointerChaserTests,
    )
