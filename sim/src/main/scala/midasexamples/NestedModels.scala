// See LICENSE for license details

package firesim.midasexamples

import chisel3._
import chisel3.experimental.annotate

import freechips.rocketchip.config.Parameters

import midas.targetutils._

class SimpleIO extends Bundle {
  val i0 = Input(UInt(4.W))
  val i1 = Input(UInt(4.W))
  val o0 = Output(UInt(4.W))
  val o1 = Output(UInt(4.W))
}

class Inner extends Module {
  val io = IO(new SimpleIO)
  io.o0 := RegNext(io.i0 + 1.U)
  io.o1 := RegNext(RegNext(io.i1 + 1.U))
}

class Mid extends Module {
  val io = IO(new SimpleIO)
  val inner = Module(new Inner)
  annotate(FAMEModelAnnotation(inner))
  inner.io.i0 := RegNext(RegNext(io.i0))
  inner.io.i1 := RegNext(io.i1)
  io.o0 := inner.io.o0
  io.o1 := inner.io.o1
}

class NestedModelsDUT extends Module {
  val io = IO(new Bundle {
    val a = new SimpleIO
    val b = new SimpleIO
  })
  val midA = Module(new Mid)
  val midB = Module(new Mid)
  annotate(EnableModelMultiThreadingAnnotation(midA))
  annotate(EnableModelMultiThreadingAnnotation(midB))
  midA.io <> io.a
  midB.io <> io.b
}

class NestedModels(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new NestedModelsDUT)
