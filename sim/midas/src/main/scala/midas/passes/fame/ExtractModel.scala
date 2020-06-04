// See LICENSE for license details.

package midas.passes.fame

import java.io.{PrintWriter, File}

import firrtl._
import firrtl.ir._
import firrtl.Utils._
import firrtl.passes.MemPortUtils
import firrtl.analyses.InstanceGraph
import annotations.{InstanceTarget, Annotation, SingleTargetAnnotation}

import midas.targetutils.FirrtlFAMEModelAnnotation

import scala.annotation.tailrec
import scala.collection.mutable
import mutable.{LinkedHashSet, LinkedHashMap}

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
    promoteModels(state)
  }
}
