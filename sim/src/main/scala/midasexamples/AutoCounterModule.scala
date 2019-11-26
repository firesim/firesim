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
    val b = Input(Bool())
  })

  val cycle = RegInit(0.U(16.W))
  val cycle4 = RegInit(false.B)

  when(io.a) { cycle := cycle + 1.U }
  PerfCounter(io.a, "CYCLES", "Count cycles. Should be identical to cycle count")


  when (cycle(2)) {
    cycle4 := true.B
  } .otherwise {
    cycle4 := false.B
  }

  PerfCounter(cycle4, "CYCLES_DIV_4", "Count the number of times the cycle count is divisable by 4. Should be equval to number of cycles divided by 4")

  val childInst = Module(new AutoCounterModuleChild)
  childInst.c := io.a
  childInst.cycle := cycle

}

class AutoCounterModuleChild extends MultiIOModule {
  val c = IO(Input(Bool()))
  val cycle = IO(Input(UInt(16.W)))

  val lfsr = chisel3.util.LFSR16(c)
  val odd_lfsr = RegInit(false.B)

  when (lfsr(0)) {
    odd_lfsr := true.B
  } .otherwise {
    odd_lfsr := false.B
  }
  PerfCounter(odd_lfsr, "ODD_LFSR", "Number of cycles the LFSR is has an odd value")
}

class AutoCounterModule extends PeekPokeMidasExampleHarness(() => new AutoCounterModuleDUT)

class AutoCounterCoverModuleDUT extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
  })

  val cycle = RegInit(0.U(12.W))
  cycle := cycle + 1.U
  val cycle8 = RegInit(false.B)


  when (cycle(3)) {
    cycle8 := true.B
  } .otherwise {
    cycle8 := false.B
  }

  cover(cycle8 , "CYCLES_DIV_8", "Count the number of times the cycle count is divisable by 8. Should be equval to number of cycles divided by 8")

  chisel3.experimental.annotate(AutoCounterCoverModuleAnnotation("AutoCounterCoverModuleDUT"))
}

class AutoCounterCoverModule extends PeekPokeMidasExampleHarness(() => new AutoCounterCoverModuleDUT)

