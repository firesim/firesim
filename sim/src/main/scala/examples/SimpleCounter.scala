// See LICENSE for license details.

package firesim.examples

import chisel3._

import org.chipsalliance.cde.config.Parameters

import firesim.lib.bridges.{PeekPokeBridge, RationalClockBridge, ResetPulseBridge, ResetPulseBridgeParameters}
import midas.targetutils.GlobalResetCondition

// DOC include start: Counter
// Simple module that when started (i.e. after reset) counts to 1000 then signals 'done'
class SimpleCounter extends Module {
  val io = IO(new Bundle {
    val done = Output(Bool())
  })

  val cnt = RegInit(0.U(16.W))

  when(cnt === 1000.U) {
    io.done := true.B
  }.otherwise {
    io.done := false.B
    cnt     := cnt + 1.U
  }

  when(cnt % 100.U === 0.U && cnt =/= 1000.U) {
    printf("Counter reached %d\n", cnt)
  }
}
// DOC include end: Counter

// DOC include start: TestHarness
// Simple example harness that runs a simulation. This harness does not terminate the simulation,
// instead it is the job of the C++ top-level to terminate the simulation.
class SimpleCounterHarness(implicit val p: Parameters) extends RawModule {
// DOC include start: ClockResetWire
  val clock = Wire(Clock())
  val reset = Wire(Bool())
// DOC include end: ClockResetWire

  // Boilerplate code:
  // The peek-poke bridge must still be instantiated even though it's
  // functionally unused. This will be removed in a future PR.
  val dummy          = WireInit(false.B)
  val peekPokeBridge = PeekPokeBridge(clock, dummy)

// DOC include start: Bridges
  // Drive with default clock provided by a bridge.
  clock := RationalClockBridge().io.clocks.head

  // Drive reset with a bridge.
  val resetBridge = Module(new ResetPulseBridge(ResetPulseBridgeParameters()))
  // In effect, the bridge counts the length of the reset in terms of this clock.
  resetBridge.io.clock := clock
  // Drive with pulsed reset for a default amount of time.
  reset                := resetBridge.io.reset
// DOC include end: Bridges

  // Boilerplate code:
  // Ensures FireSim-synthesized assertions and instrumentation is disabled
  // while 'resetBridge.io.reset' is asserted.  This ensures assertions do not fire at
  // time zero in the event their local reset is delayed (typically because it
  // has been pipelined).
  GlobalResetCondition(resetBridge.io.reset)

// DOC include start: CL
  // Custom logic.
  withClockAndReset(clock, reset) {
    val simpleCounter = Module(new SimpleCounter)

    // Print once when counter 'done' signal asserted.
    val printDone = RegInit(false.B)
    when(simpleCounter.io.done && !printDone) {
      printDone := true.B
      printf("Counter has completed!\n")
    }
  }
// DOC include end: CL
}
// DOC include end: TestHarness
