//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import freechips.rocketchip.config.Parameters
import chisel3.util.HasBlackBoxInline


class AccumulatorIO extends Bundle {
  val in  = Input(UInt(16.W))
  val out  = Output(UInt(16.W))
}

class AccumulatorDUT extends Module {
  val io = IO(new AccumulatorIO)
  val total = RegInit(0.U)
  total := total + io.in
  io.out := total
}

class VerilogAccumulatorImpl extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val in = Input(UInt(16.W))
    val out = Output(UInt(16.W))
  })
  setInline("VerilogAccumulatorImpl.v",
    """|module VerilogAccumulatorImpl(
       |    input             clock,
       |    input             reset,
       |    input      [15:0] in,
       |    output reg [15:0] out
       |);
       |  always @(posedge clock) begin
       |    if (reset)
       |      out <= 0;
       |    else
       |      out <= out + in;
       |  end
       |endmodule
       |""".stripMargin)
}

class VerilogAccumulatorDUT extends Module {
  val io = IO(new AccumulatorIO)
  val impl = Module(new VerilogAccumulatorImpl)
  impl.io.clock := clock
  impl.io.reset := reset.asBool
  impl.io.in := io.in
  io.out := impl.io.out
}

class Accumulator(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new AccumulatorDUT)
class VerilogAccumulator(implicit p: Parameters) extends PeekPokeMidasExampleHarness(() => new VerilogAccumulatorDUT)
