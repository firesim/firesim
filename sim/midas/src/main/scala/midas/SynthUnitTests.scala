// See LICENSE for license details.

package midas.unittest

import midas.core._

import chisel3._

import freechips.rocketchip.config.{Parameters, Config}
import freechips.rocketchip.unittest.UnitTests
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
      Module(new AddressRangeCounterUnitTest)
    )
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
