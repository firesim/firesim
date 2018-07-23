// See LICENSE for license details.

package midas
package unittest

import core._

import chisel3._
import firrtl.{ExecutionOptionsManager, HasFirrtlOptions}

import freechips.rocketchip.config.{Parameters, Config, Field}
import freechips.rocketchip.unittest.{UnitTests, TestHarness}


// Unittests
class WithWireChannelTests extends Config((site, here, up) => {
  case UnitTests => (q: Parameters) => {
    implicit val p = q
    val timeout = 200000
    Seq(
      Module(new WireChannelUnitTest(timeout = timeout, clockRatio = ReciprocalClockRatio(2))),
      Module(new WireChannelUnitTest(timeout = timeout, clockRatio = ReciprocalClockRatio(3))),
      Module(new WireChannelUnitTest(timeout = timeout, clockRatio = ReciprocalClockRatio(4))),
      Module(new WireChannelUnitTest(timeout = timeout, clockRatio = ReciprocalClockRatio(7))),
      Module(new WireChannelUnitTest(timeout = timeout, clockRatio = IntegralClockRatio(2))),
      Module(new WireChannelUnitTest(timeout = timeout, clockRatio = IntegralClockRatio(3))),
      Module(new WireChannelUnitTest(timeout = timeout, clockRatio = IntegralClockRatio(4))),
      Module(new WireChannelUnitTest(timeout = timeout, clockRatio = IntegralClockRatio(7))),
      Module(new WireChannelUnitTest)
    )
  }
})

class WithReadyValidChannelTests extends Config((site, here, up) => {
  case UnitTests => (q: Parameters) => {
    implicit val p = q
    val timeout = 200000
    Seq(
      Module(new ReadyValidChannelUnitTest(timeout = timeout, clockRatio = ReciprocalClockRatio(2))),
      Module(new ReadyValidChannelUnitTest(timeout = timeout, clockRatio = ReciprocalClockRatio(3))),
      Module(new ReadyValidChannelUnitTest(timeout = timeout, clockRatio = ReciprocalClockRatio(4))),
      Module(new ReadyValidChannelUnitTest(timeout = timeout, clockRatio = ReciprocalClockRatio(7))),
      Module(new ReadyValidChannelUnitTest(timeout = timeout, clockRatio = IntegralClockRatio(2))),
      Module(new ReadyValidChannelUnitTest(timeout = timeout, clockRatio = IntegralClockRatio(3))),
      Module(new ReadyValidChannelUnitTest(timeout = timeout, clockRatio = IntegralClockRatio(4))),
      Module(new ReadyValidChannelUnitTest(timeout = timeout, clockRatio = IntegralClockRatio(7))),
      Module(new ReadyValidChannelUnitTest)
    )
  }
})

// Complete configs
class AllUnitTests extends Config(new WithReadyValidChannelTests ++ new WithWireChannelTests ++ new SimConfig)

object Generator extends App with freechips.rocketchip.util.HasGeneratorUtilities {

 case class UnitTestOptions(
      configProject: String = "midas.unittest",
      config: String = "AllUnitTests") {
    val fullConfigClasses: Seq[String] = Seq(configProject + "." + config)
  }

  trait HasUnitTestOptions {
    self: ExecutionOptionsManager =>
    var utOptions = UnitTestOptions()
    parser.note("MIDAS Unit Test Generator Options")
    parser.opt[String]("config-project")
      .abbr("cp")
      .valueName("<config-project>")
      .foreach { d => utOptions = utOptions.copy(configProject = d) }
    parser.opt[String]("config")
      .abbr("conf")
      .valueName("<configClassName>")
      .foreach { cfg => utOptions = utOptions.copy(config = cfg) }
  }

  val exOptions = new ExecutionOptionsManager("regressions")
    with HasChiselExecutionOptions
    with HasFirrtlOptions
    with HasUnitTestOptions

  exOptions.parse(args)

  val params = getConfig(exOptions.utOptions.fullConfigClasses).toInstance
  Driver.execute(exOptions, () => new TestHarness()(params))
}

