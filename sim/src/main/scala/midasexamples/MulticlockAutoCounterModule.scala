//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import freechips.rocketchip.util.ResetCatchAndSync
import midas.widgets.{RationalClockBridge, PeekPokeBridge, RationalClock}


// Instantiates two of the autocounter duts from the single-clock test
// Use two separate prefixes so that we can partition the output
// from verilator/vcs and compare against the two files produced by
// the bridges
class MulticlockAutoCounterModule extends RawModule {
  val clockBridge = Module(new RationalClockBridge(RationalClock("HalfRate", 1, 2)))
  val List(refClock, div2Clock) = clockBridge.io.clocks.toList
  val reset = WireInit(false.B)
  val resetHalfRate = ResetCatchAndSync(div2Clock, reset.toBool)
  // Used to let printfs that emit the correct validation output
  val instPath = "MulticlockAutoCounterModule_AutoCounterModuleDUT"
  withClockAndReset(refClock, reset) {
    val lfsr = chisel3.util.LFSR16()
    val fullRateMod = Module(new AutoCounterModuleDUT(instPath = instPath))
    fullRateMod.io.a := lfsr(0)
    val peekPokeBridge = PeekPokeBridge(refClock, reset)
  }
  withClockAndReset(div2Clock, resetHalfRate) {
    val lfsr = chisel3.util.LFSR16()
    val fullRateMod = Module(new AutoCounterModuleDUT("AUTOCOUNTER_PRINT_HALFRATE ",
                                                      instPath = instPath + "_1"))
    fullRateMod.io.a := lfsr(0)
  }
}
