package DebugMachine

import Chisel._
import scala.collection.mutable.HashMap

// Declare daisy pins
class DaisyData(daisywidth: Int) extends Bundle {
  val in = Decoupled(UInt(width=daisywidth)).flip
  val out = Decoupled(UInt(width=daisywidth))
}

class StateData(daisywidth: Int) extends DaisyData(daisywidth) 
class SRAMData(daisywidth: Int) extends DaisyData(daisywidth) {
  val restart = Bool(INPUT)
}
class CntrData(daisywidth: Int) extends DaisyData(daisywidth)

class DaisyPins(daisywidth: Int) extends Bundle {
  val stall = Bool(INPUT)
  val state = new StateData(daisywidth)
  val sram = new SRAMData(daisywidth)
  val cntr = new CntrData(daisywidth)
}

object addDaisyPins {
  val daisyPins = HashMap[Module, DaisyPins]()
  def apply(c: Module, daisywidth: Int) = {
    val daisyPin = c.addPin(new DaisyPins(daisywidth), "daisy_pins")
    daisyPins(c) = daisyPin
    daisyPin
  }
}
