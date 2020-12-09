//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import chisel3.experimental.{withClock}

import midas.widgets.{RationalClockBridge, PeekPokeBridge}

// A simple MIDAS harness that generates a legacy
// module DUT (it has a single io: Data member) and connects all of
// its IO to a PeekPokeBridge
class PeekPokeMidasExampleHarness(dutGen: () => Module) extends RawModule {
  val clock = RationalClockBridge().io.clocks.head
  val reset = WireInit(false.B)

  withClockAndReset(clock, reset) {
    val dut = Module(dutGen())
    val peekPokeBridge = PeekPokeBridge(clock, reset, ("io", dut.io))
  }
}
