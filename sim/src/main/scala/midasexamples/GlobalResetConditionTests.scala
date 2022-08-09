//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import freechips.rocketchip.config.Parameters
import midas.targetutils._
import midas.widgets.{RationalClockBridge, PeekPokeBridge, RationalClock}

abstract class GlobalResetConditionTester(elaborator: (Bool) => Unit) extends RawModule {
  val clockBridge = RationalClockBridge(RationalClock("HalfRate", 1, 2))
  val List(clockA, clockB) = clockBridge.io.clocks.toList

  val reset = WireInit(false.B)
  // This reset will be used to mask off events (AutoCounter, Assertions,
  // Printfs) that would be active in domains that have pipelined resets
  // which may not be initially asserted.
  GlobalResetCondition(reset)

  val pipelinedResetA = withClock(clockA) { RegNext(RegNext(reset)) }
  val pipelinedResetB = withClock(clockB) { RegNext(RegNext(reset)) }

  def instantiateDomain(clock: Clock, reset: Bool) {
    withClockAndReset(clock, reset) {
      val reg = RegInit(1.U(8.W))
      // Confuse CP with an additional assignment  so that it doesn't optimize
      // through our register and render our assertion vacuous
      reg := Mux(reg =/= 0.U, 1.U, 0.U)
      val stillInReset = reg === 0.U
      elaborator(stillInReset)
    }
  }

  instantiateDomain(clockA, pipelinedResetA)
  instantiateDomain(clockB, pipelinedResetB)

  val peekPokeBridge = PeekPokeBridge(clockA, reset)
}

class AssertGlobalResetCondition(implicit p: Parameters) extends GlobalResetConditionTester(
  (inReset: Bool) => { assert(!inReset, s"This should not fire\n") }
)

class PrintfGlobalResetCondition(implicit p: Parameters) extends GlobalResetConditionTester(
  (inReset: Bool) => {
    when(inReset) { SynthesizePrintf(printf(s"This should not print. %b\n", inReset)) }
})

class AutoCounterGlobalResetCondition(implicit p: Parameters) extends GlobalResetConditionTester(
  (inReset: Bool) => {
    // Extra wire to workaround https://github.com/firesim/firesim/issues/789
    val clockWire = WireDefault(Module.clock)
    PerfCounter(inReset, clockWire, Module.reset,  "ShouldBeZero", "This should not count")
})

