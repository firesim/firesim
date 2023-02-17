//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import freechips.rocketchip.config.Parameters

import midas.widgets.{RationalClockBridge, PeekPokeBridge, RationalClock}

class RegisterModule extends Module {
  def dataType = UInt(32.W)
  val in = IO(Input(dataType))
  val out = IO(Output(dataType))
  val slowClock = IO(Input(Clock()))
  // Register the input and output in the fast domain so that the PeekPoke
  // bridge is synchronous with the design. The clock crossing is contained in this module
  val regIn  = RegNext(in)
  val regOut = Reg(in.cloneType)
  out := regOut
  withClock(slowClock) {
    regOut := RegNext(regIn)
  }
}

class TrivialMulticlock(implicit p: Parameters) extends RawModule {
  // TODO: Resolve bug in PeekPoke bridge for 3/7 case
  //val List(fullRate, halfRate, thirdRate, threeSeventhsRate) = clockBridge.io.clocks.toList

  // DOC include start: RationalClockBridge Usage
  // Here we request three target clocks (the base clock is implicit). All
  // clocks beyond the base clock are specified using the RationalClock case
  // class which gives the clock domain's name, and its clock multiplier and
  // divisor relative to the base clock.
  val clockBridge = RationalClockBridge(RationalClock("HalfRate", 1, 2),
                                        RationalClock("ThirdRate", 1, 3))

  // The clock bridge has a single output: a Vec[Clock] of the requested clocks
  // in the order they were specified, which we are now free to use through our
  // Chisel design.  While not necessary, here we unassign the Vec to give them
  // more informative references in our Chisel.
  val Seq(fullRate, halfRate, thirdRate) = clockBridge.io.clocks.toSeq
  // DOC include end: RationalClockBridge Usage
  val reset = WireInit(false.B)

  withClockAndReset(fullRate, reset) {
    val halfRateInst = Module(new RegisterModule)
    halfRateInst.slowClock := halfRate
    val thirdRateInst = Module(new RegisterModule)
    thirdRateInst.slowClock := thirdRate
    thirdRateInst.in := halfRateInst.in
    //val threeSeventhsRateInst = Module(new RegisterModule)
    // Fix peek-poke bridge under frequencies that aren't divisons of the base clock.
    //threeSeventhsRateInst.slowClock := threeSeventhsRate
    //threeSeventhsRateInst.slowClock := fullRate
    //threeSeventhsRateInst.in := halfRateInst.in

    // TODO: Remove reset
    val peekPokeBridge = PeekPokeBridge(fullRate,
                                        reset,
                                        ("in", halfRateInst.in),
                                        ("halfOut",halfRateInst.out),
                                        ("thirdOut", thirdRateInst.out))
    //                                    ("threeSeventhsOut", threeSeventhsRateInst.out))
  }
}
