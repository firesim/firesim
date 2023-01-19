//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import freechips.rocketchip.config.Parameters
import chisel3.experimental.annotate

import midas.targetutils.{FAMEModelAnnotation, FpgaDebug}

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
  when (x <= y) { y := y - x }
  when (io.e) { x := io.a; y := io.b }
  io.z := x
  io.v := y === 0.U
  // TODO: this assertion fails spuriously with deduped, extracted models
  // assert(!io.e || io.a =/= 0.U && io.b =/= 0.U, "Inputs to GCD cannot be 0")
  printf("X: %d, Y:%d\n", x, y)
  FpgaDebug(x)
}

class GCDDUT extends Module {
  val io = IO(new GCDIO)
  val inner1 = Module(new GCDInner)
  annotate(FAMEModelAnnotation(inner1))
  val inner2 = Module(new GCDInner)
  annotate(FAMEModelAnnotation(inner2))

  val select = RegInit(false.B)
  select := !select

  val done1 = RegInit(false.B)
  val result1 = Reg(UInt())

  when (io.v) {
    done1 := false.B
  } .elsewhen (inner1.io.v) {
    done1 := true.B
    result1 := inner1.io.z
  }

  val done2 = RegInit(false.B)
  val result2 = Reg(UInt())

  when (io.v) {
    done2 := false.B
  } .elsewhen (inner2.io.v) {
    done2 := true.B
    result2 := inner2.io.z
  }
  
  inner1.io.a := io.a
  inner1.io.b := io.b
  inner1.io.e := io.e

  inner2.io.a := io.b
  inner2.io.b := io.a
  inner2.io.e := io.e

  io.z := Mux(select, result1, result2)
  io.v := done1 && done2

  assert(!done1 || !done2 || (result1 === result2), "Outputs do not match!")
}

class GCD(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new GCDDUT)
