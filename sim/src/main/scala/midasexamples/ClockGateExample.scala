//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import freechips.rocketchip.config.{Parameters, Field}

import midas.widgets.{RationalClockBridge, RationalClock, PeekPokeBridge, BridgeableClockGate}

// This is used in lieu of a black box implementation. It will be replaced.
class DummyClockGate extends RawModule {
  val in  = IO(Input(Clock()))
  val en  = IO(Input(Bool()))
  val out = IO(Output(Clock()))
  dontTouch(en)
  out := in
}

class ClockGateExample(implicit p: Parameters) extends RawModule {
  val fullRate = RationalClockBridge().io.clocks.head
  val reset = WireInit(false.B)

  val clockEn = withClockAndReset(fullRate, reset) {
    val peekPokeBridge = PeekPokeBridge(fullRate, reset)
    val count = Reg(UInt(64.W))
    count := count + 1.U
    printf(p"Clock A Cycle: $count\n")

    val clockEn = Reg(Bool())
    when (count % 1024.U === 0.U) {
      clockEn := ~clockEn
    }
    clockEn
  }

  val clockGate = Module(new DummyClockGate)
  clockGate.in := fullRate
  clockGate.en := clockEn
  val gatedClock = clockGate.out

  // Annotate the gater indicating it can be replaced with a model
  BridgeableClockGate(clockGate, clockGate.in, clockGate.out, clockGate.en)

  // Reset is only provided here because chisel needs one to instantiate a register
  withClockAndReset(gatedClock, reset) {
    val count = Reg(UInt(64.W))
    count := count + 1.U
    printf(p"gatedClock: $count\n")
  }
}

