//See LICENSE for license details.

package firesim.midasexamples

import chisel3._
import chisel3.experimental.{withClock, RawModule, MultiIOModule}

import midas.widgets.{RationalClockBridge, PeekPokeBridge}

class RegisterModule extends MultiIOModule {
  def dataType = UInt(32.W)
  val in = IO(Input(dataType))
  val out = IO(Output(dataType))
  out := RegNext(in)
}

class TrivialMulticlock extends RawModule {
  val clockBridge = Module(new RationalClockBridge(1000, (1,2), (1,3), (3,7)))
  val List(fullRate, halfRate, thirdRate, threeSeventhsRate) = clockBridge.io.clocks.toList
  val reset = Wire(Bool())

  withClockAndReset(fullRate, reset) {
    val halfRateInst = Module(new RegisterModule)
    halfRateInst.clock := halfRate
    val thirdRateInst = Module(new RegisterModule)
    thirdRateInst.clock := thirdRate
    thirdRateInst.in := halfRateInst.in
    val threeSeventhsRateInst = Module(new RegisterModule)
    threeSeventhsRateInst.clock := threeSeventhsRate
    threeSeventhsRateInst.in := halfRateInst.in

    val peekPokeBridge = PeekPokeBridge(fullRate, reset, ("in", halfRateInst.in),
                                                         ("halfOut", halfRateInst.out),
                                                         ("thirdOut", thirdRateInst.out),
                                                         ("threeSeventhsOut", threeSeventhsRateInst.out))
  }
}

