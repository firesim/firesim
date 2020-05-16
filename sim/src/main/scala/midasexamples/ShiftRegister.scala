//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import freechips.rocketchip.config.Parameters

class ShiftRegisterDUT extends Module {
  val io = IO(new Bundle {
    val in  = Input(UInt(1.W))
    val out = Output(UInt(1.W))
  })
  val r0 = RegNext(io.in)
  val r1 = RegNext(r0)
  val r2 = RegNext(r1)
  val r3 = RegNext(r2)
  io.out := r3
}

class ShiftRegister(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new ShiftRegisterDUT)
