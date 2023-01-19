
//See LICENSE for license details.

package firesim.midasexamples

import freechips.rocketchip.config.Parameters
import chisel3._
import chisel3.util._

class PipeModule[T <: Data](gen: T, latency: Int = 0) extends Module {
  val in   = IO(Input(gen))
  val out  = IO(Output(gen))
  out := ShiftRegister(in, latency)
}

object PipeModule {
  def apply[T <: Data](in: T, latency: Int = 0): T = {
    val m = Module(new PipeModule(in.cloneType, latency))
    m.in := in
    m.out
  }
}

class WireInterconnectDUT extends Module {
  def aType = UInt(16.W)
  def bType = new Bundle {
    val foo = SInt(4.W)
    val bar = Valid(UInt(128.W))
  }

  val io = IO(new Bundle {
    val aIn   = Input(aType)
    val aOut  = Output(aType)
    val bIn   = Input(bType)
    val bOut  = Output(bType)
  })

  io.aOut := PipeModule(io.aIn, 0)
  io.bOut := PipeModule(io.bIn, 1)
}

class WireInterconnect(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new WireInterconnectDUT)
