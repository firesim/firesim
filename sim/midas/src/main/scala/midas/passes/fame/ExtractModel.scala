// See LICENSE for license details.

package midas.passes.fame


import firrtl._
import firrtl.analyses.InstanceGraph

import midas.targetutils.FirrtlFAMEModelAnnotation

import scala.annotation.tailrec

class ExtractModel extends Transform {
  def inputForm = HighForm
  def outputForm = HighForm

  @tailrec
  private def promoteModels(state: CircuitState): CircuitState = {
    val fmAnns = state.annotations.collect({
      case ann: FirrtlFAMEModelAnnotation => ann.target.module -> ann
    }).toMap
    // Pick by order of parent in linearization -- don't pick children of main
    val modOrder = (new InstanceGraph(state.circuit)).moduleOrder.filterNot(_.name == state.circuit.main)
    val topModelAnn = modOrder.collectFirst(Function.unlift(dm => fmAnns.get(dm.name)))
    topModelAnn match {
      case None => state
      case Some(FirrtlFAMEModelAnnotation(it)) =>
        val anns = PromoteSubmoduleAnnotation(it) +: state.annotations
        val nextPromoted = (new PromoteSubmodule).runTransform(state.copy(annotations = anns))
        promoteModels(nextPromoted)
    }
  }

  override def execute(state: CircuitState): CircuitState = {
    val transformed = promoteModels(state)
    // At this point, the FIRRTLFAMEModelAnnotations are no longer used, so remove them for cleanup.
    // FAMEDefaults uses structure of "AQB form" to infer that all top-level, non-channel modules are FAME models.
    transformed.copy(annotations = transformed.annotations.filterNot(_.isInstanceOf[FirrtlFAMEModelAnnotation]))
  }
}
