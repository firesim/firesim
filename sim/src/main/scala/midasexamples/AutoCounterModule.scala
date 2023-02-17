//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters

import midas.targetutils.{AutoCounterCoverModuleAnnotation}
import firesim.midasexamples.AutoCounterWrappers.{PerfCounter, cover}



/**
  * Demonstrates how to instantiate autocounters, and validates those
  * autocounter by comparing their output against a printf that should emit the
  * same strings
  *
  */
class AutoCounterModuleDUT(val instName: String = "dut")(implicit val v: AutoCounterValidator) extends Module
    with AutoCounterTestContext {
  val io = IO(new Bundle {
    val a = Input(Bool())
  })

  val enabled_cycles = RegInit(0.U(16.W))

  when(io.a) { enabled_cycles := enabled_cycles + 1.U }

  PerfCounter(io.a, "ENABLED", "Enabled cycles, should be identical to cycle count minus reset cycles")

  val enabled4 = ~enabled_cycles(1) & ~enabled_cycles(0) & io.a

  PerfCounter(enabled4, "ENABLED_DIV_4", "Count the number of times the enabled cycle count is divisible by 4. Should be equal to number of cycles minus reset cycles divided by 4")

  // Multibit event
  val count = RegInit(0.U(4.W))
  count := count + 1.U
  PerfCounter(count, "MULTIBIT_EVENT", "A multibit event. Here's a quote: \" and a comma: , to check description serialization.")

  val childInst = Module(new AutoCounterModuleChild)
  childInst.io.c := io.a

  // Check that identity operation preserves the annotated target
  val identityCounter = RegInit(0.U(64.W))
  identityCounter := 0x01234567DEADBEEFL.U
  PerfCounter.identity(identityCounter, "PASSTHROUGH", "A multibit that is preserved and not accumulated")

  v.generateValidationPrintfs()
}

class AutoCounterModuleChild(val instName: String = "child")(implicit val v: AutoCounterValidator) extends Module
    with AutoCounterTestContext {
  val io =  IO(new Bundle {
    val c = Input(Bool())
  })

  val lfsr = chisel3.util.random.LFSR(16, io.c)

  val odd_lfsr = lfsr(0)

  PerfCounter(odd_lfsr, "ODD_LFSR", "Number of cycles the LFSR is has an odd value")
}

/** Demonstrate explicit instrumentation of AutoCounters via PerfCounter
 *
 * Toplevel Chisel class suitable for use as a GoldenGate 'target' as described by the docs
 *
 * @see https://docs.fires.im/en/latest/Advanced-Usage/Debugging-and-Profiling-on-FPGA/AutoCounter.html#ad-hoc-performance-counters
 * @see https://docs.fires.im/en/latest/Advanced-Usage/Generating-Different-Targets.html
 * @see AutoCounterF1Test
 * @see PerfCounter
 */
class AutoCounterModule(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() =>
  new AutoCounterModuleDUT()(new AutoCounterValidator))

class AutoCounterCoverModuleDUT(val instName: String = "dut")(implicit val v: AutoCounterValidator = new AutoCounterValidator) extends Module with AutoCounterTestContext {
  freechips.rocketchip.util.property.cover.setPropLib(new midas.passes.FireSimPropertyLibrary())
  val io = IO(new Bundle {
    val a = Input(Bool())
  })

  val cycle = RegInit(0.U(12.W))
  cycle := cycle + 1.U
  val cycle8 = ~cycle(2) & ~cycle(1) & ~cycle(0)

  cover(cycle8 , "CYCLES_DIV_8", "Count the number of times the cycle count is divisible by 8. Should be equal to number of cycles divided by 8")

  chisel3.experimental.annotate(AutoCounterCoverModuleAnnotation(Module.currentModule.get.toTarget))

  //--------VALIDATION---------------

  v.generateValidationPrintfs()
}

/** Demonstrate implicit instrumentation of AutoCounters via RocketChip 'cover' functions
 *
 * Toplevel Chisel class suitable for use as a GoldenGate 'target' as described by the docs
 *
 * @see https://docs.fires.im/en/latest/Advanced-Usage/Debugging-and-Profiling-on-FPGA/AutoCounter.html#rocket-chip-cover-functions
 * @see freechips.rocketchip.util.property.cover
 * @see https://docs.fires.im/en/latest/Advanced-Usage/Generating-Different-Targets.html
 * @see AutoCounterCoverModuleF1Test
 */
class AutoCounterCoverModule(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new AutoCounterCoverModuleDUT)

class AutoCounterPrintfDUT extends Module {
  val io = IO(new Bundle {
    val a = Input(Bool())
  })

  implicit val v = new AutoCounterValidator(autoCounterPrintfMode = true)
  val childInst = Module(new AutoCounterModuleChild())
  childInst.io.c := io.a

  val incrementer = RegInit(0.U(32.W))
  incrementer := incrementer + 1.U
  PerfCounter.identity(incrementer, "incrementer", "Should print on every cycle after reset is disabled.")

  // Randomly update an identity value
  val lfsr64 = chisel3.util.random.LFSR(64)
  val lfsr64Reg = RegEnable(lfsr64, (lfsr64(2,0) === 0.U))
  PerfCounter.identity(lfsr64Reg, "lfsr64_three_lsbs_zero", "Should print when the LFSR transitions to a value with three LSBs unset.")

  v.generateValidationPrintfs()
}

/** Demonstrate alternative impementation of AutoCounters using event-driven SynthesizedPrintf's
 *
 * Toplevel Chisel class suitable for use as a GoldenGate 'target' as described by the docs
 *
 * @see https://docs.fires.im/en/latest/Advanced-Usage/Debugging-and-Profiling-on-FPGA/AutoCounter.html#autocounter-using-synthesizable-printfs
 * @see https://docs.fires.im/en/latest/Advanced-Usage/Generating-Different-Targets.html
 * @see AutoCounterPrintfF1Test
 */
class AutoCounterPrintfModule(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new AutoCounterPrintfDUT)

