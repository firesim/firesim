//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import freechips.rocketchip.config.{Parameters, Field, Config}
import midas.widgets.{RationalClockBridge, PeekPokeBridge, ResetPulseBridge, ResetPulseBridgeParameters}

case object ResetPulseBridgeActiveHighKey extends Field[Boolean](true)
class ResetPulseBridgeActiveLowConfig extends Config((site, here, up) => {
  case ResetPulseBridgeActiveHighKey => false
})

object ResetPulseBridgeTestConsts {
  val maxPulseLength = 1023
}

/**
  * Instantiates the ResetClockBridge, and checks the created pulse matches an
  * elaboration-time defined expected value with an unsynthesized assert.
  *
  * @param p Parameters instance. See above for relavent keys.
  */

class ResetPulseBridgeTest(implicit p: Parameters) extends RawModule {
  import ResetPulseBridgeTestConsts._
  val clock = RationalClockBridge().io.clocks.head

  // TODO Remove once PeekPoke is excised from simif
  val dummy = WireInit(false.B)
  val peekPokeBridge = PeekPokeBridge(clock, dummy)

  val activeHigh = p(ResetPulseBridgeActiveHighKey)
  val resetBridge = Module(new ResetPulseBridge(ResetPulseBridgeParameters(
    activeHigh = activeHigh,
    // This will be overridden to the maxPulseLength by the bridge's plusArg
    defaultPulseLength = 1,
    maxPulseLength = maxPulseLength)))
  resetBridge.io.clock := clock

  withClockAndReset(clock, false.B) {
    // Zero initialized
    val cycle = Reg(UInt(32.W))
    cycle := cycle + 1.U
    printf(p"Cycle: ${cycle} Reset: ${resetBridge.io.reset}\n")
    assert((cycle < maxPulseLength.U) ^ (resetBridge.io.reset ^ activeHigh.B))
  }
}
