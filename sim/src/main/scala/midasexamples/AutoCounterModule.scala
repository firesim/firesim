//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import freechips.rocketchip.config.Parameters

import midas.targetutils.{PerfCounter, AutoCounterCoverModuleAnnotation}
import freechips.rocketchip.util.property._

class AutoCounterModuleDUT(
  printfPrefix: String = "AUTOCOUNTER_PRINT ",
  instPath: String = "AutoCounterModule_AutoCounterModuleDUT",
  clockDivision: Int = 1) extends Module {
  val io = IO(new Bundle {
    val a = Input(Bool())
  })

  val enabled_cycles = RegInit(0.U(16.W))

  when(io.a) { enabled_cycles := enabled_cycles + 1.U }

  PerfCounter(io.a, "ENABLED", "Enabled cycles. Should be identical to cycle count minus reset cycles")

  val enabled4 = ~enabled_cycles(1) & ~enabled_cycles(0) & io.a

  PerfCounter(enabled4, "ENABLED_DIV_4", "Count the number of times the enabled cycle count is divisible by 4. Should be equal to number of cycles minus reset cycles divided by 4")

  val childInst = Module(new AutoCounterModuleChild)
  childInst.io.c := io.a

  //--------VALIDATION---------------

  val samplePeriod = 1000 / clockDivision
  val enabled_printcount = freechips.rocketchip.util.WideCounter(64, io.a)
  val enabled4_printcount = freechips.rocketchip.util.WideCounter(64, enabled4)
  val oddlfsr_printcount = freechips.rocketchip.util.WideCounter(64, childInst.io.oddlfsr)
  val cycle_print = Reg(UInt(64.W))
  cycle_print := cycle_print + 1.U
  when ((cycle_print >= (samplePeriod - 1).U) & (cycle_print % samplePeriod.U === (samplePeriod - 1).U)) {
    printf(s"${printfPrefix}Cycle %d\n", cycle_print)
    printf(s"${printfPrefix}============================\n")
    printf(s"${printfPrefix}PerfCounter ENABLED_${instPath}: %d\n", enabled_printcount)
    printf(s"${printfPrefix}PerfCounter ENABLED_DIV_4_${instPath}: %d\n", enabled4_printcount)
    printf(s"${printfPrefix}PerfCounter ODD_LFSR_${instPath}_childInst: %d\n", oddlfsr_printcount)
    printf(s"${printfPrefix}\n")
  }
}

class AutoCounterModuleChild extends Module {
  val io =  IO(new Bundle {
    val c = Input(Bool())
    val oddlfsr = Output(Bool())
  })

  val lfsr = chisel3.util.random.LFSR(16, io.c)

  val odd_lfsr = lfsr(0)

  PerfCounter(odd_lfsr, "ODD_LFSR", "Number of cycles the LFSR is has an odd value")

  io.oddlfsr := odd_lfsr
}

class AutoCounterModule(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new AutoCounterModuleDUT)

class AutoCounterCoverModuleDUT extends Module {
  cover.setPropLib(new midas.passes.FireSimPropertyLibrary())
  val io = IO(new Bundle {
    val a = Input(Bool())
  })

  val cycle = RegInit(0.U(12.W))
  cycle := cycle + 1.U
  val cycle8 = ~cycle(2) & ~cycle(1) & ~cycle(0)

  cover(cycle8 , "CYCLES_DIV_8", "Count the number of times the cycle count is divisible by 8. Should be equal to number of cycles divided by 8")

  chisel3.experimental.annotate(AutoCounterCoverModuleAnnotation("AutoCounterCoverModuleDUT"))

  //--------VALIDATION---------------

  val cycle8_printcount = RegInit(0.U(64.W))
  when (cycle8) {
    cycle8_printcount := cycle8_printcount + 1.U
  }
  val samplePeriod = 1000
  val cycle_print = Reg(UInt(64.W))
  cycle_print := cycle_print + 1.U
  when ((cycle_print >= (samplePeriod - 1).U) & (cycle_print % 1000.U === (samplePeriod - 1).U)) {
    printf("AUTOCOUNTER_PRINT Cycle %d\n", cycle_print)
    printf("AUTOCOUNTER_PRINT ============================\n")
    printf("AUTOCOUNTER_PRINT PerfCounter CYCLES_DIV_8_AutoCounterCoverModule_AutoCounterCoverModuleDUT: %d\n", cycle8_printcount)
    printf("AUTOCOUNTER_PRINT \n")
  }

}

class AutoCounterCoverModule(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new AutoCounterCoverModuleDUT)

class AutoCounterPrintfDUT extends Module {
  val io = IO(new Bundle {
    val a = Input(Bool())
  })

  val childInst = Module(new AutoCounterModuleChild)
  childInst.io.c := io.a

  //--------VALIDATION---------------

  val oddlfsr_printcount = freechips.rocketchip.util.WideCounter(64, childInst.io.oddlfsr)
  val cycle_print = Reg(UInt(39.W))
  cycle_print := cycle_print + 1.U
  when (childInst.io.oddlfsr) {
    printf("SYNTHESIZED_PRINT CYCLE: %d [AutoCounter] ODD_LFSR: %d\n", cycle_print, oddlfsr_printcount)
  }
}

class AutoCounterPrintfModule(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new AutoCounterPrintfDUT)

