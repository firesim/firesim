//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import freechips.rocketchip.config.{Parameters, Field}

import midas.widgets.{RationalClockBridge, RationalClock, PeekPokeBridge, BridgeableClockDivider}

class ClockDivider(implicit p: Parameters) extends RawModule {

  val fullRate = RationalClockBridge().io.clocks.head
  val reset = WireInit(false.B)

  val clockDivider = Module(new freechips.rocketchip.util.ClockDivider2)
  clockDivider.io.clk_in := fullRate
  val halfRate = clockDivider.io.clk_out

  // Annotate the divider indicating it can be replaced with a model
  BridgeableClockDivider(
    clockDivider,
    clockDivider.io.clk_in,
    clockDivider.io.clk_out,
    div = 2)

  // Reset is only provided here because chisel needs one to instantiate a register
  val slowClockCount = withClockAndReset(halfRate, reset) {
    val count = Reg(UInt(64.W))
    count := count + 1.U
    printf(p"Slow Clock Cycle: $count\n")
    count
  }

  withClockAndReset(fullRate, reset) {
    val peekPokeBridge = PeekPokeBridge(fullRate, reset)
    val count = Reg(UInt(64.W))
    count := count + 1.U
    val slowClockCountReg = RegNext(slowClockCount)
    printf(p"Fast Clock Cycle: $count\n")
    assert((count >> 1.U) === slowClockCountReg)
  }
}

