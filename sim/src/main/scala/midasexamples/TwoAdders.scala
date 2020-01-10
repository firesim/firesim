//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import chisel3.util.unless
import chisel3.experimental.{withClock, annotate, RawModule}

import midas.widgets.PeekPokeBridge
import midas.targetutils._


class AdderIO extends Bundle {
  val x = Input(UInt(16.W))
  val y = Input(UInt(16.W))
  val z = Output(UInt(16.W))
}

class PipeAdder extends Module {
  val io = IO(new AdderIO)
  val mem = Mem(2, UInt(16.W))
  mem.write(1.U, io.x + io.y)
  val memout = mem.read(1.U)
  io.z := memout
}

class DoublePipeAdder extends Module {
  val io = IO(new AdderIO)
  val logic = Module(new PipeAdder)
  logic.io.x := io.x
  logic.io.y := io.y
  io.z := RegNext(logic.io.z)
}

class TwoAddersDUT extends Module {
  val io = IO(new Bundle {
    val i0 = Input(UInt(16.W))
    val i1 = Input(UInt(16.W))
    val i2 = Input(UInt(16.W))
    val i3 = Input(UInt(16.W))
    val o0 = Output(UInt(16.W))
    val o1 = Output(UInt(16.W))
  })

  val a0 = Module(new DoublePipeAdder)
  annotate(FAMEModelAnnotation(a0))
  annotate(EnableModelMultiThreadingAnnotation(a0))
  a0.io.x := io.i0
  a0.io.y := io.i1
  io.o0 := a0.io.z

  val a1 = Module(new DoublePipeAdder)
  annotate(FAMEModelAnnotation(a1))
  annotate(EnableModelMultiThreadingAnnotation(a1))
  a1.io.x := io.i2
  a1.io.y := io.i3
  io.o1 := a1.io.z
}

class TwoAdders extends PeekPokeMidasExampleHarness(() => new TwoAddersDUT)
