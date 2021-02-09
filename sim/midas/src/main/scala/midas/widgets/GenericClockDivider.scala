// See LICENSE for license details.

package midas.widgets

import chisel3._
import chisel3.util._
import chisel3.experimental.BaseModule

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.unittest.{UnitTest}

/**
  * This models a generic integer divider, whose first output positive edge is
  * coincident with the first input positive edge.
  *
  * Note: The output clock does not have a 50% duty cycle for odd divisions.
  */
class GenericClockDividerN(div: Int) extends MultiIOModule {
  val clk_in   = IO(Flipped(new TimestampedTuple(Bool())))
  val clk_out  = IO(new TimestampedTuple(Bool()))

  val highTransition = (div / 2) - 1;
  val lowTransition = div - 1
  val edgeCount = RegInit(highTransition.U(log2Ceil(div).W))

  val reg = RegInit({
    val w = Wire(ValidIO(new TimestampedToken(Bool())))
    w := DontCare
    w.valid := false.B
    w
  })

  when(clk_out.fire) {
    reg := clk_out.latest
  }

  clk_out.old := reg
  clk_out.latest.valid := clk_in.latest.valid
  clk_out.latest.bits.time := clk_in.latest.bits.time
  clk_out.latest.bits.data := reg.bits.data

  // We only potentially stall when there's a positive edge on the input.
  // Could do better and only potentially stall on output transitions...
  clk_in.observed := clk_out.observed || clk_in.unchanged || (clk_out.old.valid && !clk_in.latest.bits.data)
  val iPosedge = clk_in.latest.valid && !clk_in.unchanged && clk_in.latest.bits.data

  // Time Zero
  when(!clk_out.old.valid) {
    when(clk_out.observed) {
      edgeCount := Mux(clk_in.latest.bits.data, (highTransition + 1).U, highTransition.U)
    }
    clk_out.latest.bits.data := clk_in.latest.bits.data
  // All subsequent input posedges
  }.elsewhen(iPosedge) {
    when(clk_out.observed) {
      edgeCount := Mux(edgeCount === lowTransition.U, 0.U, edgeCount + 1.U)
    }
    when(edgeCount === highTransition.U) {
      clk_out.latest.bits.data := true.B
    }.elsewhen(edgeCount === lowTransition.U) {
      clk_out.latest.bits.data := false.B
    }
  }

  // Use last-connect semantics to optimize the above logic away
  if (div == 1) {
    clk_out <> clk_in
  }
}

object GenericClockDividerN {
  private class ReferenceClockDividerN(div: Int) extends BlackBox(Map("DIV" -> div)) with HasBlackBoxResource {
    require(div > 0);
    val io = IO(new Bundle {
      val clk_out = Output(Clock())
      val clk_in  = Input(Clock())
    })
    addResource("/midas/widgets/ClockDividerN.sv")
  }

  def instantiateAgainstReference(clockIn: (Bool, TimestampedTuple[Bool]), div: Int): (Bool, TimestampedTuple[Bool]) = {

    val refClockDiv = Module(new ReferenceClockDividerN(div))
    refClockDiv.io.clk_in := clockIn._1.asClock

    val modelClockDiv = Module(new GenericClockDividerN(div))
    modelClockDiv.clk_in <> clockIn._2
    (refClockDiv.io.clk_out.asBool, modelClockDiv.clk_out)
  }
}

class GenericClockDividerNTest(
    clockPeriodPS: Int,
    div: Int,
    timeout: Int = 50000)(implicit p: Parameters) extends UnitTest(timeout) {

  val clockTuple = ClockSource.instantiateAgainstReference(clockPeriodPS, initValue = false)
  val (reference, model) = GenericClockDividerN.instantiateAgainstReference(clockTuple, div)
  io.finished := TimestampedTokenTraceEquivalence(reference, model, timeout)
}
