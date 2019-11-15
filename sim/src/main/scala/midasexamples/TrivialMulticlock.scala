//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import chisel3.experimental.{withClock, RawModule, MultiIOModule}

import midas.widgets.{RationalClockBridge, PeekPokeBridge}

class HalfRateModule extends MultiIOModule {
  val in = IO(Input(Bool()))
  val out = IO(Output(Bool()))
  out := RegNext(in)
}

class TrivialMulticlock extends RawModule {
  val clockBridge = Module(new RationalClockBridge(1000, (1,2)))
  val fullRate = clockBridge.io.clocks(0)
  val halfRate = clockBridge.io.clocks(1)
  // Dummy reset for now
  val reset = Wire(Bool())

  withClockAndReset(halfRate, reset) {
    val halfRateInst = Module(new HalfRateModule)
    val peekPokeBridge = PeekPokeBridge(fullRate, reset, ("in", halfRateInst.in), ("out", halfRateInst.out))
  }
}

