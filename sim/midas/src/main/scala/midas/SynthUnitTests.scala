// See LICENSE for license details.

package midas.unittest

import midas.core._

import chisel3._
import firrtl.{ExecutionOptionsManager, HasFirrtlOptions}

import freechips.rocketchip.config.{Parameters, Config, Field}
import freechips.rocketchip.unittest.{UnitTests, TestHarness}
import midas.models.{CounterTableUnitTest, LatencyHistogramUnitTest, AddressRangeCounterUnitTest}


// Unittests
class WithAllUnitTests extends Config((site, here, up) => {
  case UnitTests => (q: Parameters) => {
    implicit val p = q
    val timeout = 2000000
    Seq(
      Module(new PipeChannelUnitTest(latency = 0, timeout = timeout)),
      Module(new PipeChannelUnitTest(latency = 1, timeout = timeout)),
      Module(new ReadyValidChannelUnitTest(timeout = timeout)),
      Module(new CounterTableUnitTest),
      Module(new LatencyHistogramUnitTest),
      Module(new AddressRangeCounterUnitTest))
  }
})

// Failing tests
class WithTimeOutCheck extends Config((site, here, up) => {
  case UnitTests => (q: Parameters) => {
    implicit val p = q
    Seq(
      Module(new PipeChannelUnitTest(timeout = 100)),
    )
  }
})

// Complete configs
class AllUnitTests extends Config(new WithAllUnitTests ++ new midas.SimConfig)
class TimeOutCheck extends Config(new WithTimeOutCheck ++ new midas.SimConfig)

// Generates synthesizable unit tests for key modules, such as simulation channels
// See: src/main/cc/unittest/Makefile for the downstream RTL-simulation flow
//
// TODO: Make the core of this generator a trait that can be mixed into
// FireSim's ScalaTests for more type safety
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

