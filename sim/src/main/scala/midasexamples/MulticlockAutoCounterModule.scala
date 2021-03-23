//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import freechips.rocketchip.util.ResetCatchAndSync
import freechips.rocketchip.config.Parameters
import midas.widgets.{RationalClockBridge, PeekPokeBridge, RationalClock}


// Instantiates two of the autocounter duts from the single-clock test
// Use two separate prefixes so that we can partition the output
// from verilator/vcs and compare against the two files produced by
// the bridges
class MulticlockAutoCounterModule(implicit p: Parameters) extends RawModule {
  val clockBridge = RationalClockBridge(RationalClock("ThirdRate", 1, 3))
  val List(refClock, div2Clock) = clockBridge.io.clocks.toList
  val reset = WireInit(false.B)
  val resetHalfRate = ResetCatchAndSync(div2Clock, reset.asBool)
  // Used to let printfs that emit the correct validation output
  val instPath = "MulticlockAutoCounterModule_AutoCounterModuleDUT"
  withClockAndReset(refClock, reset) {
    val lfsr = chisel3.util.random.LFSR(16)
    val fullRateMod = Module(new AutoCounterModuleDUT(instPath = instPath))
    fullRateMod.io.a := lfsr(0)
    val peekPokeBridge = PeekPokeBridge(refClock, reset)
  }
  withClockAndReset(div2Clock, resetHalfRate) {
    val lfsr = chisel3.util.random.LFSR(16)
    val fullRateMod = Module(new AutoCounterModuleDUT("AUTOCOUNTER_PRINT_THIRDRATE ",
                                                      instPath = instPath + "_1",
                                                      clockDivision = 3))
    fullRateMod.io.a := lfsr(0)
  }
}
