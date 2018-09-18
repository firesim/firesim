//See LICENSE for license details.

package firesim.midasexamples

import midas.passes.FpgaDebugAnnotation

import chisel3._
import chisel3.util.unless

class GCD extends Module {
  val io = IO(new Bundle {
    val a  = Input(UInt(16.W))
    val b  = Input(UInt(16.W))
    val e  = Input(Bool())
    val z  = Output(UInt(16.W))
    val v  = Output(Bool())
  })
  val x  = Reg(UInt())
  val y  = Reg(UInt())
  when   (x > y) { x := x - y }
  unless (x > y) { y := y - x }
  when (io.e) { x := io.a; y := io.b }
  io.z := x
  io.v := y === 0.U

  chisel3.experimental.annotate(FpgaDebugAnnotation(x))
  chisel3.experimental.annotate(FpgaDebugAnnotation(y))
}
