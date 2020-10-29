//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import freechips.rocketchip.config.{Parameters, Field}

import midas.widgets.{RationalClockBridge, RationalClock, PeekPokeBridge, BridgeableClockMux, BridgeableClockDivider}

class ClockMux(implicit p: Parameters) extends RawModule {
  val fullRate = RationalClockBridge().io.clocks.head

  val clockDivider = Module(new freechips.rocketchip.util.ClockDivider2)
  clockDivider.io.clk_in := fullRate
  val halfRate = clockDivider.io.clk_out

  // Annotate the divider indicating it can be replaced with a model
  BridgeableClockDivider(
    clockDivider,
    clockDivider.io.clk_in,
    clockDivider.io.clk_out,
    div = 2)

  val clockMux = Module(new testchipip.ClockMux2)
  clockMux.io.clocksIn(0) := fullRate
  clockMux.io.clocksIn(1) := halfRate

  // Annotate the CMux indicating it can be replaced with a model
  BridgeableClockMux(
    clockMux,
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

