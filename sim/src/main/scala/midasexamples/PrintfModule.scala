//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import chisel3.experimental.MultiIOModule

import midas.targetutils.SynthesizePrintf

class PrintfModule extends MultiIOModule {
  val a = IO(Input(Bool()))
  val b = IO(Input(Bool()))

  val cycle = RegInit(0.U(16.W))

  when(a) { cycle := cycle + 1.U }

  printf(SynthesizePrintf("A: %d\n", cycle))
  when(b) { printf(SynthesizePrintf("B asserted\n")) } // Argument-less print

  val wideArgument = VecInit(Seq.fill(33)(WireInit(cycle))).asUInt
  printf(SynthesizePrintf("wideArgument: %x\n", wideArgument)) // argument width > DMA width

  val childInst = Module(new PrintfModuleChild)
  childInst.c := a
  childInst.d := cycle
}

class PrintfModuleChild extends MultiIOModule {
  val c = IO(Input(Bool()))
  val d = IO(Input(UInt(16.W)))

  when (c ^ d(0) && d > 16.U) {
    printf(SynthesizePrintf("C: %b, D: %d\n", c, d))
  }
}

