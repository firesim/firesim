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
  val clockBridge = RationalClockBridge(RationalClock("SlowClock", 1, 3))
  val List(refClock, slowClock) = clockBridge.io.clocks.toList
  val reset = WireInit(false.B)
  val slowReset = ResetCatchAndSync(slowClock, reset.asBool)
  // Used to let printfs that emit the correct validation output
  val instPath = "MulticlockAutoCounterModule_AutoCounterModuleDUT"
  withClockAndReset(refClock, reset) {
    val lfsr = chisel3.util.random.LFSR(16)
    val fullRateMod = Module(new AutoCounterModuleDUT(instName = "secondRate")(new AutoCounterValidator))
    fullRateMod.io.a := lfsr(0)
    val peekPokeBridge = PeekPokeBridge(refClock, reset)
  }

  withClockAndReset(slowClock, slowReset) {
    val lfsr = chisel3.util.random.LFSR(16)
    val fullRateMod = Module(new AutoCounterModuleDUT("slowClock")(
      new AutoCounterValidator(
        domainName = "SlowClock",
        printfPrefix = "AUTOCOUNTER_PRINT_SLOWCLOCK ",
        clockDivision = 3)))
    fullRateMod.io.a := lfsr(0)
  }
}
