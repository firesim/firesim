//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import chisel3.util.LFSR16
import chisel3.core.MultiIOModule

import midas.targetutils.{PerfCounter, AutoCounterCoverModuleAnnotation}
import freechips.rocketchip.util.property._

class AutoCounterModuleDUT extends Module {
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

  val enabled_printcount = freechips.rocketchip.util.WideCounter(64, io.a)
  val enabled4_printcount = freechips.rocketchip.util.WideCounter(64, enabled4)
  val oddlfsr_printcount = freechips.rocketchip.util.WideCounter(64, childInst.io.oddlfsr)
  val cycle_print = Reg(UInt(64.W))
  cycle_print := cycle_print + 1.U
  when ((cycle_print >= 1000.U) & (cycle_print % 1000.U === 0.U)) {
    printf("AUTOCOUNTER_PRINT Cycle %d\n", cycle_print)
    printf("AUTOCOUNTER_PRINT ============================\n")
    printf("AUTOCOUNTER_PRINT PerfCounter ENABLED_AutoCounterModule_AutoCounterModuleDUT: %d\n", enabled_printcount)
    printf("AUTOCOUNTER_PRINT PerfCounter ENABLED_DIV_4_AutoCounterModule_AutoCounterModuleDUT: %d\n", enabled4_printcount)
    printf("AUTOCOUNTER_PRINT PerfCounter ODD_LFSR_AutoCounterModule_AutoCounterModuleDUT_childInst: %d\n", oddlfsr_printcount)
    printf("AUTOCOUNTER_PRINT \n")
  }
}

class AutoCounterModuleChild extends MultiIOModule {
  val io =  IO(new Bundle {
    val c = Input(Bool())
    val oddlfsr = Output(Bool())
  })

  val lfsr = chisel3.util.LFSR16(io.c)

  val odd_lfsr = lfsr(0)

  PerfCounter(odd_lfsr, "ODD_LFSR", "Number of cycles the LFSR is has an odd value")

  io.oddlfsr := odd_lfsr
}

class AutoCounterModule extends PeekPokeMidasExampleHarness(() => new AutoCounterModuleDUT)

class AutoCounterCoverModuleDUT extends Module {
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
  val cycle_print = Reg(UInt(64.W))
  cycle_print := cycle_print + 1.U
  when ((cycle_print >= 1000.U) & (cycle_print % 1000.U === 0.U)) {
    printf("AUTOCOUNTER_PRINT Cycle %d\n", cycle_print)
    printf("AUTOCOUNTER_PRINT ============================\n")
    printf("AUTOCOUNTER_PRINT PerfCounter CYCLES_DIV_8_AutoCounterCoverModule_AutoCounterCoverModuleDUT: %d\n", cycle8_printcount)
    printf("AUTOCOUNTER_PRINT \n")
  }

}

class AutoCounterCoverModule extends PeekPokeMidasExampleHarness(() => new AutoCounterCoverModuleDUT)

