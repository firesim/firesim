//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import freechips.rocketchip.config.Parameters
import midas.widgets.{RationalClockBridge, PeekPokeBridge, RationalClock}

class ChildModule extends Module {
  val io = IO(new Bundle {
    val pred = Input(Bool())
  })
  assert(!io.pred, "Pred asserted")
}
class AssertModuleDUT extends Module {
  val io = IO(new Bundle {
    val cycleToFail  = Input(UInt(16.W))
    val a  = Input(Bool())
    val b  = Input(Bool())
    val c  = Input(Bool())
  })
  val cycle = RegInit(0.U(16.W))
  cycle := cycle + 1.U

  assert(io.cycleToFail =/= cycle || !io.a, "A asserted")
  when (io.cycleToFail === cycle) {
    assert(!io.b, "B asserted")
  }

  val cMod = Module(new ChildModule)
  cMod.io.pred := io.c && io.cycleToFail === cycle
}

class AssertModule(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new AssertModuleDUT)

class RegisteredAssertModule extends Module {
  val io = IO(new Bundle {
    val pred = Input(Bool())
  })
  assert(!RegNext(io.pred), "Pred asserted")
}

class DualClockModule extends Module {
  val io = IO(new Bundle {
    val clockB = Input(Clock())
    val a  = Input(Bool())
    val b  = Input(Bool())
    val c  = Input(Bool())
    val d  = Input(Bool())
  })

  withClock(io.clockB) {
    assert(!RegNext(io.a), "io.a asserted")
    val modB = Module(new RegisteredAssertModule)
    modB.io.pred := io.b
  }

  assert(!RegNext(io.c), "io.c asserted")
  val modA = Module(new RegisteredAssertModule)
  modA.io.pred := io.d
}

class StimulusGenerator extends Module {
  val input = IO(new Bundle {
    val cycle       = Input(UInt(16.W))
    val pulseLength = Input(UInt(4.W))
  })
  val pred = IO(Output(Bool()))
  // Here i'm relying on zero-intialization of state instead of reset
  val cycleCount = Reg(UInt(16.W))
  cycleCount := cycleCount + 1.U

  val pulseLengthRemaining = Reg(UInt(4.W))
  when(input.cycle === cycleCount && input.pulseLength =/= 0.U) {
    pulseLengthRemaining := input.pulseLength - 1.U
  }.elsewhen(pulseLengthRemaining =/= 0.U) {
    pulseLengthRemaining := pulseLengthRemaining - 1.U
  }

  pred := pulseLengthRemaining =/= 0.U || input.cycle === cycleCount
}


class MulticlockAssertModule(implicit p: Parameters) extends RawModule {
  val clockBridge = RationalClockBridge(RationalClock("HalfRate", 1, 2))
  val List(refClock, div2Clock) = clockBridge.io.clocks.toList
  val reset = WireInit(false.B)
  withClockAndReset(refClock, reset) {
    val fullRateMod = Module(new RegisteredAssertModule)
    val fullRatePulseGen = Module(new StimulusGenerator)
    fullRateMod.io.pred := fullRatePulseGen.pred

    val halfRateMod = Module(new RegisteredAssertModule)
    halfRateMod.clock := div2Clock
    val halfRatePulseGen = Module(new StimulusGenerator)
    halfRateMod.io.pred := halfRatePulseGen.pred

    val peekPokeBridge = PeekPokeBridge(refClock, reset,
                                        ("fullrate", fullRatePulseGen.input),
                                        ("halfrate", halfRatePulseGen.input))
  }
}
