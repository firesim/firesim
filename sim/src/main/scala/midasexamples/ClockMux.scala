//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import freechips.rocketchip.config.{Parameters, Field}

import midas.widgets.{RationalClockBridge, RationalClock, PeekPokeBridge, BridgeableClockMux}

class ClockMux(implicit p: Parameters) extends RawModule {
  val clockBridge = RationalClockBridge(RationalClock("HalfRate", 1, 2))
  val Seq(fullRate, halfRate) = clockBridge.io.clocks.toSeq

  val clockMux = Module(new testchipip.ClockMux2)
  clockMux.io.clocksIn := clockBridge.io.clocks

  // Annotate the CMux indicating it can be replaced with a model
  BridgeableClockMux(clockMux,
    clockMux.io.clocksIn(0),
    clockMux.io.clocksIn(1),
    clockMux.io.clockOut,
    clockMux.io.sel)

  val reset = WireInit(false.B)

  withClockAndReset(fullRate, reset) {
    val peekPokeBridge = PeekPokeBridge(fullRate, reset)
    val count = Reg(UInt(64.W))
    count := count + 1.U
    printf(p"Clock A Cycle: $count\n")
  }
  // Reset is only provided here because chisel needs one to instantiate a register
  withClockAndReset(halfRate, reset) {
    val count = Reg(UInt(64.W))
    count := count + 1.U
    printf(p"Clock B Cycle: $count\n")
  }

  // Reset is only provided here because chisel needs one to instantiate a register
  withClockAndReset(clockMux.io.clockOut, reset) {
    val count = Reg(UInt(64.W))
    count := count + 1.U
    printf(p"Clock Dynamic Cycle: $count\n")

    val sel = Reg(Bool())
    when (count % 1024.U === 0.U) {
      sel := ~sel
    }
    clockMux.io.sel := sel
  }
}

