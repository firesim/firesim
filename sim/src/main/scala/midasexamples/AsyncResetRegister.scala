// See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import chisel3.experimental.chiselName
import freechips.rocketchip.config.{Parameters, Field}

import midas.widgets.{RationalClockBridge, PeekPokeBridge, AsyncResetPulseSource}

/**
  *  This is a smoke test for async reset support. An async reset pulse is
  *  provided to (and removed from) target register before the first positive
  *  edge of its clock. Under the old system, this register would not take on
  *  its reset value.
  */


class AResetModule extends RawModule {
  val regOut = IO(Output(Bool()))
  val clock  = IO(Input(Clock()))
  val areset = IO(Input(AsyncReset()))

  @chiselName
  class ChiselNameableImpl {
    val aresetReg = RegInit(true.B)
    dontTouch(aresetReg)
    // Not reset, but relies on 0-initialization
    val afterFirstEdge = Reg(Bool())
    when (!afterFirstEdge) {
      afterFirstEdge := true.B
    }
    assert(!afterFirstEdge || aresetReg, "aresetReg was not asynchronously reset before receiving its first clock edge.")
    regOut := aresetReg
  }
  withClockAndReset(clock, areset) { new ChiselNameableImpl }
}

class AsyncResetRegister(implicit p: Parameters) extends RawModule {
  val clock = RationalClockBridge().io.clocks.head
  val areset = AsyncResetPulseSource(500)

  val mod = Module(new AResetModule)
  mod.clock := clock
  mod.areset := areset
  // This to placate parts of the compiler that still expects to find a peek-poke bridge
  val dummy = Wire(Bool())
  val peekPokeBridge = PeekPokeBridge(clock, dummy, "regOut" -> mod.regOut)
}

