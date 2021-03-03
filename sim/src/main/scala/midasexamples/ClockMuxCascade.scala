//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import freechips.rocketchip.config.{Field, Parameters, Config}

import midas.widgets.{RationalClockBridge, RationalClock, PeekPokeBridge, BridgeableClockMux, BridgeableClockDivider}

/**
  * Instantiates a cascade of 2:1 clock muxes. Each stage chooses between the clock
  * selected by the previous stage, or a locally generate clock that has been
  * divided down N times from the reference, where N is the index of the stage.
  *
  * This serves as a resource scalability and FMR test for multiclock support.
  *
  * @param A parameters instance with the Field [[NumClockDomains]] set.
  */

class ClockMuxCascade(implicit p: Parameters) extends RawModule {

  class RegisterModule extends MultiIOModule {
    val in = IO(Input(Bool()))
    val out = IO(Output(Bool()))
    out := RegNext(in)
  }

  val reset = WireInit(false.B)
  val fullRate = RationalClockBridge().io.clocks.head

  def instantiante(input: List[RegisterModule], idx: Int): List[RegisterModule] = idx match {
    case 0 => input
    case idx =>
      val prevClock = input.headOption match {
        case Some(mod) => mod.clock
        case None => fullRate
      }

      // Placeholder target module
      val clockDivider = Module(new freechips.rocketchip.util.ClockDivider2)
      clockDivider.io.clk_in := fullRate
      val localClock = clockDivider.io.clk_out

      // Annotate the divider indicating it can be replaced with a model
      BridgeableClockDivider(
        clockDivider,
        clockDivider.io.clk_in,
        clockDivider.io.clk_out,
        div = idx + 1)

      // Placeholder target module
      val clockMux = Module(new testchipip.ClockMux2)
      clockMux.io.clocksIn(0) := localClock
      clockMux.io.clocksIn(1) := prevClock

      // Annotate the CMux indicating it can be replaced with a model
      BridgeableClockMux(
        clockMux,
        clockMux.io.clocksIn(0),
        clockMux.io.clocksIn(1),
        clockMux.io.clockOut,
        clockMux.io.sel)

      val regMod = withClockAndReset(localClock, reset) {
        val regMod = Module(new RegisterModule())
        regMod
      }
      clockMux.io.sel := regMod.out
      input.headOption foreach { prev => regMod.in := prev.out }
      instantiante(regMod +: input, idx - 1)
  }

  val regMods = instantiante(Nil, p(NumClockDomains))

  withClock(fullRate) {
    val peekPokeBridge = PeekPokeBridge(fullRate, reset, ("out", regMods.head.out))

    val count = Reg(UInt(64.W))
    val sel = Reg(Bool())
    when (count % 1024.U === 0.U) {
      sel := ~sel
    }
    regMods.last.in := sel
  }
}
