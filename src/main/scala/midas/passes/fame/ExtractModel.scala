// See LICENSE for license details.

package midas.passes.fame

import java.io.{PrintWriter, File}

import firrtl._
import ir._
import Mappers._
import Utils._
import firrtl.passes.MemPortUtils
import annotations.{InstanceTarget, Annotation, SingleTargetAnnotation}

import scala.collection.mutable
import mutable.{LinkedHashSet, LinkedHashMap}

class ExtractModel extends Transform {
  def inputForm = HighForm
  def outputForm = HighForm

  def promoteModels(state: CircuitState): CircuitState = {
    val anns = state.annotations.flatMap {
      case a @ FAMEModelAnnotation(it) if (it.module != it.circuit) => Seq(a, PromoteSubmoduleAnnotation(it))
      case a => Seq(a)
    }
    if (anns.toSeq == state.annotations.toSeq) {
      state
    } else {
      promoteModels((new PromoteSubmodule).runTransform(state.copy(annotations = anns)))
    }
  }

  override def execute(state: CircuitState): CircuitState = {
    promoteModels(state)
  }
}
