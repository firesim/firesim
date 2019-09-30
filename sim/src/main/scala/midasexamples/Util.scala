//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import chisel3.experimental.{withClock, RawModule}

import midas.widgets.PeekPokeEndpoint

// A simple MIDAS harness that generates a legacy
// module DUT (it has a single io: Data member) and connects all of
// its IO to a PeekPokeEndpoint
class PeekPokeMidasExampleHarness(dutGen: () => Module) extends RawModule {
  val clock = IO(Input(Clock()))
  val reset = WireInit(false.B)

  withClockAndReset(clock, reset) {
    val dut = Module(dutGen())
    val peekPokeEndpoint = PeekPokeEndpoint(reset, ("io", dut.io))
  }
}
