// See LICENSE for license details

package firesim.midasexamples

import chisel3._
import chisel3.experimental.annotate

import freechips.rocketchip.config.Parameters

import midas.widgets.PeekPokeBridge
import midas.targetutils._

class SimpleIO extends Bundle {
    val i = Input(UInt(4.W))
    val o = Output(UInt(4.W))
}

class Inner extends Module {
  val io = IO(new SimpleIO)
  io.o := RegNext(io.i + 1.U)
}

class Mid extends Module {
  val io = IO(new SimpleIO)
  val inner = Module(new Inner)
  annotate(FAMEModelAnnotation(inner))
  inner.io.i := RegNext(io.i)
  io.o := inner.io.o
}

class NestedModelsDUT extends Module {
  val io = IO(new Bundle {
    val a = new SimpleIO
    val b = new SimpleIO
  })
  val midA = Module(new Mid)
  val midB = Module(new Mid)
  annotate(FAMEModelAnnotation(midA))
  annotate(FAMEModelAnnotation(midB))
  annotate(EnableModelMultiThreadingAnnotation(midA))
  annotate(EnableModelMultiThreadingAnnotation(midB))
  midA.io <> io.a
  midB.io <> io.b
}

class NestedModels(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new NestedModelsDUT)
