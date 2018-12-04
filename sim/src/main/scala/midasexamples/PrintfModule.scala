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
}

