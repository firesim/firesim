//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import freechips.rocketchip.config.{Parameters, Field}

import midas.widgets._

class ClockMuxAndGate(implicit p: Parameters) extends RawModule {
  val fullRate = RationalClockBridge().io.clocks.head

  val clockDivider = Module(new freechips.rocketchip.util.ClockDivider2)
  clockDivider.io.clk_in := fullRate
  val halfRate = clockDivider.io.clk_out

  // Annotate the divider indicating it can be replaced with a model
  BridgeableClockDivider(
    clockDivider,
    clockDivider.io.clk_in,
    clockDivider.io.clk_out,
    div = 2,
    p(ClockDividerStyleKey))

  val clockMux = Module(new testchipip.ClockMux2)
  clockMux.io.clocksIn(0) := fullRate
  clockMux.io.clocksIn(1) := halfRate

  // Annotate the CMux indicating it can be replaced with a model
  BridgeableClockMux(
    clockMux,
    clockMux.io.clocksIn(0),
    clockMux.io.clocksIn(1),
    clockMux.io.clockOut,
    clockMux.io.sel,
    p(ClockMuxStyleKey))

  val reset = WireInit(false.B)

  val clockGate = Module(new DummyClockGate)
  clockGate.in := clockMux.io.clockOut

  // Annotate the gater indicating it can be replaced with a model
  BridgeableClockGate(clockGate, clockGate.in, clockGate.out, clockGate.en)

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
  withClockAndReset(clockGate.out, reset) {

    val count = Reg(UInt(64.W))
    count := count + 1.U
    printf(p"gatedClock: $count\n")
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

    val en = Reg(Bool())
    when (count % 512.U === 0.U) {
      en := ~en
    }
    clockMux.io.sel := sel
    clockGate.en := en
  }

}

