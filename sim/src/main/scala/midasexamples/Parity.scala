//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import freechips.rocketchip.config.Parameters
import chisel3.util.Enum

class ParityDUT extends Module {
  val io = IO(new Bundle {
    val in  = Input(Bool())
    val out = Output(Bool())
  })
  val s_even :: s_odd :: Nil = Enum(2)
  val state  = RegInit(s_even)
  when (io.in) {
    when (state === s_even) { state := s_odd  }
    .otherwise              { state := s_even }
  }
  io.out := (state === s_odd)
}

class Parity(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new ParityDUT)
