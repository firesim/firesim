// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import Mappers._
import ir._
import annotations._

/*
 * Run after channel excision Collects all connectivity annotations (these now
 * run to between I/O on the wrapper, and _instances_ of a model, and emits
 * FAMEChannelPortAnnotations on the module of the model.
 */
class InferModelPorts extends Transform {
  def inputForm = LowForm
  def outputForm = LowForm

  override def execute(state: CircuitState): CircuitState = {
    val analysis = new FAMEChannelAnalysis(state, FAME1Transform)
    val cTarget = CircuitTarget(state.circuit.main)
    val modelChannelPortsAnnos = analysis.modulePortDedupers.flatMap(deduper =>
      deduper.completePortMap.map({ case (cName, ports) =>
        FAMEChannelPortsAnnotation(cName, ports.map(p => deduper.mTarget.ref(p.name)))
      }))
    state.copy(annotations = state.annotations ++ modelChannelPortsAnnos)
  }
}
