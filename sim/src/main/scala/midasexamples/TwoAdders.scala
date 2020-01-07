//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import chisel3.util.unless
import chisel3.experimental.{withClock, annotate, RawModule}

import midas.widgets.PeekPokeBridge
import midas.targetutils.FAMEModelAnnotation

class PipeAdder extends Module {
  val io = IO(new Bundle {
    val x = Input(UInt(16.W))
    val y = Input(UInt(16.W))
    val z = Output(UInt(16.W))
  })

  io.z := RegNext(io.x + io.y)
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

  val a0 = Module(new PipeAdder)
  annotate(FAMEModelAnnotation(a0))
  a0.io.x := io.i0
  a0.io.y := io.i1
  io.o0 := a0.io.z

  val a1 = Module(new PipeAdder)
  annotate(FAMEModelAnnotation(a1))
  a1.io.x := io.i2
  a1.io.y := io.i3
  io.o1 := a1.io.z
}

class TwoAdders extends PeekPokeMidasExampleHarness(() => new TwoAddersDUT)
