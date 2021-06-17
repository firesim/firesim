//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.util.ResetCatchAndSync
import midas.widgets.{RationalClockBridge, PeekPokeBridge, RationalClock}


// Instantiates two of the printf duts from the single-clock test
// Use two separate prefixes so that we can partition the output
// from verilator/vcs and compare against the two files produced by
// the bridges
class MulticlockPrintfModule(implicit p: Parameters) extends RawModule {
  val clockBridge = RationalClockBridge(RationalClock("HalfRate",1 , 2))
  val List(refClock, div2Clock) = clockBridge.io.clocks.toList
  val reset = WireInit(false.B)
  val resetHalfRate = ResetCatchAndSync(div2Clock, reset.asBool)
  withClockAndReset(refClock, reset) {
    val lfsr = chisel3.util.random.LFSR(16)
    val fullRateMod = Module(new PrintfModuleDUT)
    fullRateMod.io.a := lfsr(0)
    fullRateMod.io.b := ~lfsr(0)
    val peekPokeBridge = PeekPokeBridge(refClock, reset)
  }
  withClockAndReset(div2Clock, resetHalfRate) {
    val lfsr = chisel3.util.random.LFSR(16)
    val fullRateMod = Module(new PrintfModuleDUT("SYNTHESIZED_PRINT_HALFRATE "))
    fullRateMod.io.a := lfsr(0)
    fullRateMod.io.b := ~lfsr(0)
  }
}
