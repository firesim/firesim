// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import firrtl.ir._
import firrtl.annotations._
import firrtl.transforms.DontTouchAnnotation

/*
 * Run after channel excision Collects all connectivity annotations (these now
 * run to between I/O on the wrapper, and _instances_ of a model, and emits
 * FAMEChannelPortAnnotations on the module of the model.
 */
class InferModelPorts extends Transform {
  def inputForm = LowForm
  def outputForm = LowForm

  def portAnnos(mt: ModuleTarget, cName: String, clk: Option[Port], ports: Seq[Port]): Seq[Annotation] = {
    val clkRT = clk.map(p => mt.ref(p.name))
    val portsRT = ports.map(p => mt.ref(p.name))
    val fcpa = FAMEChannelPortsAnnotation(cName, clkRT, portsRT)
    // Label all the channel ports with don't touch so as to prevent
    // annotation renaming from breaking downstream
    fcpa +: (clkRT ++: portsRT).map(DontTouchAnnotation(_))
  }

  override def execute(state: CircuitState): CircuitState = {
    val analysis = new FAMEChannelAnalysis(state)
    val cTarget = CircuitTarget(state.circuit.main)
    val modelChannelPortsAnnos = analysis.modulePortDedupers.flatMap {
      case deduper => deduper.completePortMap.flatMap {
        case (cName, (clk, ports)) => portAnnos(deduper.mTarget, cName, clk, ports)
      }
    }
    state.copy(annotations = state.annotations ++ modelChannelPortsAnnos)
  }
}
