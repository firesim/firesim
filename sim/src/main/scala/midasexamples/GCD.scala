//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import chisel3.util.unless
import chisel3.experimental.{withClock, annotate, RawModule}

import midas.widgets.PeekPokeBridge
import midas.targetutils.FAMEModelAnnotation

class GCDIO extends Bundle {
  val a  = Input(UInt(16.W))
  val b  = Input(UInt(16.W))
  val e  = Input(Bool())
  val z  = Output(UInt(16.W))
  val v  = Output(Bool())
}

class GCDInner extends Module {
  val io = IO(new GCDIO)
  val x  = Reg(UInt())
  val y  = Reg(UInt())
  when   (x > y) { x := x - y }
  unless (x > y) { y := y - x }
  when (io.e) { x := io.a; y := io.b }
  io.z := x
  io.v := y === 0.U

  assert(!io.e || io.a =/= 0.U && io.b =/= 0.U, "Inputs to GCD cannot be 0")
  printf("X: %d, Y:%d\n", x, y)
}

class GCDDUT extends Module {
  val io = IO(new GCDIO)
  val inner = Module(new GCDInner)
  annotate(FAMEModelAnnotation(inner))
  io <> inner.io
}

class GCD extends PeekPokeMidasExampleHarness(() => new GCDDUT)
