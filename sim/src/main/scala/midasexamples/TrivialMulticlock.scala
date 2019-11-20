//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import chisel3.experimental.{RawModule, MultiIOModule}

import midas.widgets.{RationalClockBridge, PeekPokeBridge}

class RegisterModule extends MultiIOModule {
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

class TrivialMulticlock extends RawModule {
  // TODO: Resolve bug in PeekPoke bridge for 3/7 case
  //val clockBridge = Module(new RationalClockBridge(1000, (1,2), (1,3), (3,7)))
  //val List(fullRate, halfRate, thirdRate, threeSeventhsRate) = clockBridge.io.clocks.toList
  val clockBridge = Module(new RationalClockBridge(1000, (1,2), (1,3)))
  val List(fullRate, halfRate, thirdRate) = clockBridge.io.clocks.toList
  val reset = WireInit(false.B)

  withClockAndReset(fullRate, reset) {
    val halfRateInst = Module(new RegisterModule)
    halfRateInst.slowClock := halfRate
    val thirdRateInst = Module(new RegisterModule)
    thirdRateInst.slowClock := thirdRate
    thirdRateInst.in := halfRateInst.in
    val threeSeventhsRateInst = Module(new RegisterModule)
    // TODO: See above
    //threeSeventhsRateInst.slowClock := threeSeventhsRate
    threeSeventhsRateInst.slowClock := fullRate
    threeSeventhsRateInst.in := halfRateInst.in

    // TODO: Remove reset
    val peekPokeBridge = PeekPokeBridge(fullRate,
                                        reset,
                                        ("in", halfRateInst.in),
                                        ("halfOut",halfRateInst.out),
                                        ("thirdOut", thirdRateInst.out),
                                        ("threeSeventhsOut", threeSeventhsRateInst.out))
  }
}
