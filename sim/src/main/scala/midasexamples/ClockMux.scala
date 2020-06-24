//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import freechips.rocketchip.config.{Parameters, Field}

import midas.widgets.{ClockBridgeWithMux, PeekPokeBridge}

class ClockMux(implicit p: Parameters) extends RawModule {
  val clockGen = Module(new ClockBridgeWithMux)
    val reset = WireInit(false.B)

  withClockAndReset(clockGen.io.clockA, reset) {
    val peekPokeBridge = PeekPokeBridge(clockGen.io.clockA, reset)
    val count = Reg(UInt(64.W))
    count := count + 1.U
    printf(p"Clock A Cycle: $count\n")
  }
  // Reset is only provided here because chisel needs one to instantiate a register
  withClockAndReset(clockGen.io.clockB, reset) {
    val count = Reg(UInt(64.W))
    count := count + 1.U
    printf(p"Clock B Cycle: $count\n")
  }

  // Reset is only provided here because chisel needs one to instantiate a register
  withClockAndReset(clockGen.io.clockDynamic, reset) {
    val count = Reg(UInt(64.W))
    count := count + 1.U
    printf(p"Clock Dynamic Cycle: $count\n")

    val sel = Reg(Bool())
    when (count % 1024.U === 0.U) {
      sel := ~sel
    }
    clockGen.io.sel := sel
  }
}

