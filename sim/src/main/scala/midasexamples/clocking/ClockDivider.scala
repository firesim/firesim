//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import freechips.rocketchip.config.{Parameters, Field}

import midas.widgets.{BlackBoxClockSourceBridge, ClockSourceParams, PeekPokeBridge, BridgeableClockDivider}

class ClockDivider(implicit p: Parameters) extends RawModule {

  val fullRate = Module(new BlackBoxClockSourceBridge(ClockSourceParams(1024, initValue = false))).io.clockOut
  val reset = WireInit(false.B)

  val clockDivider = Module(new freechips.rocketchip.util.ClockDivider2)
  clockDivider.io.clk_in := fullRate
  val halfRate = clockDivider.io.clk_out

  // Annotate the divider indicating it can be replaced with a model
  BridgeableClockDivider(
    clockDivider,
    clockDivider.io.clk_in,
    clockDivider.io.clk_out,
    2,
    p(ClockDividerStyleKey))

  // Reset is only provided here because chisel needs one to instantiate a register
  val slowClockCount = withClockAndReset(halfRate, reset) {
    val count = Reg(UInt(64.W)) // Assumes init value 0
    count := count + 1.U
    printf(p"Slow Clock Cycle: $count\n")
    count
  }

  withClockAndReset(fullRate, reset) {
    val peekPokeBridge = PeekPokeBridge(fullRate, reset)
    val count = Reg(UInt(64.W)) // Assumes init value 0
    count := count + 1.U
    printf(p"Fast Clock Cycle: $count\n")
    // Even edges will have no simultaenous slow-clock edge, so we can avoid a data race
    when(count(0)) {
      assert(((count + 1.U) >> 1.U) === slowClockCount)
    }
  }
}

