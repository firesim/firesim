//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import chisel3.util.LFSR16
import chisel3.experimental.MultiIOModule

import midas.targetutils.{PerfCounter, AutoCounterCoverModuleAnnotation}
import freechips.rocketchip.util.property._

class AutoCounterModuleDUT extends Module {
  val io = IO(new Bundle {
    val a = Input(Bool())
  })

  val cycle = RegInit(0.U(16.W))
  val cycle4 = RegInit(false.B)

  //when(io.a) { cycle := cycle + 1.U }
  cycle := cycle + 1.U

  PerfCounter(io.a, "CYCLES", "Count cycles. Should be identical to cycle count minus reset cycles")


  when (~cycle(1) & ~cycle(0) & io.a) {
    cycle4 := true.B
  } .otherwise {
    cycle4 := false.B
  }


  PerfCounter(cycle4, "CYCLES_DIV_4", "Count the number of times the cycle count is divisable by 4. Should be equval to number of cycles divided by 4")

  val childInst = Module(new AutoCounterModuleChild)
  childInst.io.c := io.a

  //--------VALIDATION---------------

  val cycle_printcount = RegInit(0.U(64.W))
  val cycle4_printcount = RegInit(0.U(64.W))
  val oddlfsr_printcount = RegInit(0.U(64.W))

  when (cycle4) {
    cycle4_printcount := cycle4_printcount + 1.U
  }
  when (io.a) {
    cycle_printcount := cycle_printcount + 1.U
  }
  when (childInst.io.oddlfsr) {
    oddlfsr_printcount := oddlfsr_printcount + 1.U
  }

  when ((cycle >= 999.U) & ((cycle - 999.U) % 1000.U === 0.U)) {
    printf("AUTOCOUNTER_PRINT Cycle %d\n", cycle)
    printf("AUTOCOUNTER_PRINT ============================\n")
    printf("AUTOCOUNTER_PRINT PerfCounter CYCLES_AutoCounterModule_AutoCounterModuleDUT: %d\n", cycle_printcount)
    printf("AUTOCOUNTER_PRINT PerfCounter CYCLES_DIV_4_AutoCounterModule_AutoCounterModuleDUT: %d\n", cycle4_printcount)
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
  val odd_lfsr = RegInit(false.B)

  when (lfsr(0)) {
    odd_lfsr := true.B
  } .otherwise {
    odd_lfsr := false.B
  }

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
  val cycle8 = RegInit(false.B)


  when (~cycle(2) & ~cycle(1) & ~cycle(0)) {
    cycle8 := true.B
  } .otherwise {
    cycle8 := false.B
  }

  cover(cycle8 , "CYCLES_DIV_8", "Count the number of times the cycle count is divisable by 8. Should be equval to number of cycles divided by 8")

  chisel3.experimental.annotate(AutoCounterCoverModuleAnnotation("AutoCounterCoverModuleDUT"))

  //--------VALIDATION---------------

  val cycle8_printcount = RegInit(0.U(64.W))
  when (cycle8) {
    cycle8_printcount := cycle8_printcount + 1.U
  }

  when ((cycle >= 999.U) & ((cycle - 999.U) % 1000.U === 0.U)) {
    printf("AUTOCOUNTER_PRINT Cycle %d\n", cycle)
    printf("AUTOCOUNTER_PRINT ============================\n")
    printf("AUTOCOUNTER_PRINT PerfCounter CYCLES_DIV_8_AutoCounterCoverModule_AutoCounterCoverModuleDUT: %d\n", cycle8_printcount)
    printf("AUTOCOUNTER_PRINT \n")
  }

}

class AutoCounterCoverModule extends PeekPokeMidasExampleHarness(() => new AutoCounterCoverModuleDUT)

