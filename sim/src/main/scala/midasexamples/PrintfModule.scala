//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import chisel3.util.LFSR16
import chisel3.experimental.MultiIOModule

import midas.targetutils.SynthesizePrintf

class PrintfModule extends MultiIOModule {
  val a = IO(Input(Bool()))
  val b = IO(Input(Bool()))

  val cycle = RegInit(0.U(16.W))

  when(a) { cycle := cycle + 1.U }

  // Printf format strings must be prefixed with "SYNTHESIZED_PRINT CYCLE: %d"
  // so they can be pulled out of RTL simulators log and sorted within a cycle
  // As the printf order will be different betwen RTL simulator and synthesized stream
  printf(SynthesizePrintf("SYNTHESIZED_PRINT CYCLE: %d\n", cycle))

  val wideArgument = VecInit(Seq.fill(33)(WireInit(cycle))).asUInt
  printf(SynthesizePrintf("SYNTHESIZED_PRINT CYCLE: %d wideArgument: %x\n", cycle, wideArgument)) // argument width > DMA width

  val childInst = Module(new PrintfModuleChild)
  childInst.c := a
  childInst.cycle := cycle

  printf(SynthesizePrintf("thi$!sn+taS/\neName", "SYNTHESIZED_PRINT CYCLE: %d constantArgument: %x\n", cycle, 1.U(8.W)))
}

class PrintfModuleChild extends MultiIOModule {
  val c = IO(Input(Bool()))
  val cycle = IO(Input(UInt(16.W)))

  val lfsr = chisel3.util.LFSR16(c)
  printf(SynthesizePrintf("SYNTHESIZED_PRINT CYCLE: %d LFSR: %x\n", cycle, lfsr))

  //when (lsfr(0)) {
  //  printf(SynthesizePrintf(p"SYNTHESIZED_PRINT CYCLE: ${cycle} LFSR is odd"))
  //}
}

class NarrowPrintfModule extends MultiIOModule {
  val enable = IO(Input(Bool()))
  val cycle = RegInit(0.U(12.W))
  cycle := cycle + 1.U
  when(LFSR16()(0) & LFSR16()(0) & enable) {
    printf(SynthesizePrintf("SYNTHESIZED_PRINT CYCLE: %d\n", cycle(5,0)))
  }
}
