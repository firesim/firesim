// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import annotations.{ReferenceTarget}

import scala.collection.mutable
import firrtl.options.Dependency

/**
  * This transform adds [[FAMEChannelFanoutAnnotations]] to collections of channels
  * that have a common set of sources (they are driven by the same model). This is later used
  * in SimulationMapping to emit forking channels.
  */
object AddRemainingFanoutAnnotations extends Transform with DependencyAPIMigration {
  // Only after the FAME transform do the source RTs for model-sourced channels
  // that fanout to both models and bridges agree.
	override def prerequisites = Seq(Dependency[FAMETransform])
  // Fanout annotations are used exclusively by sim wrapper generation
  override def optionalPrerequisiteOf = Seq(Dependency[midas.passes.SimulationMapping])

  override def execute(state: CircuitState): CircuitState = {
    val modelSourcedFCCAs = new mutable.LinkedHashMap[Seq[ReferenceTarget], mutable.Set[String]] with mutable.MultiMap[Seq[ReferenceTarget], String] {
      // To preserve per-key insertion orer
      override def makeSet: mutable.Set[String] = new mutable.LinkedHashSet[String]
    }

    state.annotations.collect {
      case FAMEChannelConnectionAnnotation(name, PipeChannel(_), _, Some(srcs), _) =>
        modelSourcedFCCAs.addBinding(srcs, name)
    }

    // Generate [[FAMEChannelFanoutAnnotation]] for model-sourced channels that fan out.
    val fanoutAnnos = modelSourcedFCCAs.values.collect {
      case names if names.size > 1 => FAMEChannelFanoutAnnotation(names.toSeq)
    }
    state.copy(annotations = state.annotations ++ fanoutAnnos)
  }
}
