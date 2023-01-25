// See LICENSE for license details.

package midas.passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._

/** Connects the host clock to each model in the top-level
  *
  * This maintains the current bad assumption that the host clock is named
  * "clock" but this has been changed in the multiclock branch.
  */

private[passes] object ConnectHostClock extends firrtl.Transform {

  def inputForm = HighForm
  def outputForm = HighForm

  def execute(state: CircuitState): CircuitState = {
    val c = state.circuit
    val topName = state.circuit.main
    val topModule = state.circuit.modules.find(_.name == topName).get
    val hostClock = WRef(topModule.ports.find(_.name == "clock").get)

    def addClockConnects(s: Statement): Statement = s.map(addClockConnects) match {
      case inst: WDefInstance => Block(inst, Connect(NoInfo, WSubField(WRef(inst), "clock"), hostClock))
      case s => s
    }

    def onModule(m: DefModule): DefModule = m match {
      case m if m.name == topName => m.map(addClockConnects)
      case s => s
    }
    state.copy(circuit = state.circuit.map(onModule))
  }
}
