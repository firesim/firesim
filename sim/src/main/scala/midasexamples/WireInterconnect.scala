
//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import chisel3.util._
import chisel3.experimental.MultiIOModule

class PipeModule[T <: Data](gen: T, latency: Int = 0) extends MultiIOModule {
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

class WireInterconnect extends MultiIOModule {
  def aType = UInt(16.W)
  def bType = new Bundle {
    val foo = SInt(4.W)
    val bar = Valid(UInt(128.W))
  }

  val aIn   = IO(Input(aType) )
  val aOut  = IO(Output(aType))
  val bIn   = IO(Input(bType) )
  val bOut  = IO(Output(bType))

  aOut := PipeModule(aIn, 0)
  bOut := PipeModule(bIn, 1)
}
