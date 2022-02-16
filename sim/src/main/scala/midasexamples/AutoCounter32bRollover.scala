//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import freechips.rocketchip.config.Parameters
import firesim.midasexamples.AutoCounterWrappers.{PerfCounter}

/**
  * Check that auto-counter output that would rollover a 32b boundary is
  * captured correctly. This is an important boundary since under the current
  * implementations accumulation registers are divided into 32b segments for
  * MMIO.
  */
class AutoCounter32bRollover(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() =>
  new AutoCounter32bRolloverDUT()(new AutoCounterValidator))

class AutoCounter32bRolloverDUT(val instName: String = "dut")(implicit val v: AutoCounterValidator) extends Module
    with AutoCounterTestContext {

  val io = IO(new Bundle{val a = Input(Bool())})

  val largeIncrement = WireDefault((BigInt(1) << 31).U(32.W))
  PerfCounter(largeIncrement, "two_to_the_31", "Should not rollover the default accumulation width")

  v.generateValidationPrintfs
}

