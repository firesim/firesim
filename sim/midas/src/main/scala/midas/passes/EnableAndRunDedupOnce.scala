// See LICENSE for license details.

package midas.passes

import firrtl.{Transform, DependencyAPIMigration, CircuitState}

/**
  * Aggressive deduplication during phases of the Golden Gate flow where the target
  * design is still being transformed can cause issues with bridge annotations that
  * define connectivity. To work around this issue, the compiler relies on a global
  * NoCircuitDedupAnnotation; however, this transform ignores the annotation to run
  * dedup a single time after target transformation completes rather than several
  * times as it would if enabled globally.
  */
class EnableAndRunDedupOnce extends Transform with DependencyAPIMigration {
  override def prerequisites = firrtl.stage.Forms.Resolved
  override def optionalPrerequisites = Nil
  override def optionalPrerequisiteOf = Nil
  override def invalidates(a: Transform) = false

  override def execute(state: CircuitState): CircuitState = {
    val (noDedupAnnos, otherAnnos) = state.annotations.partition {
      case firrtl.transforms.NoCircuitDedupAnnotation => true
      case _ => false
    }
    val deduped = (new firrtl.transforms.DedupModules).execute(state.copy(annotations = otherAnnos))
    deduped.copy(annotations = deduped.annotations ++ noDedupAnnos)
  }
}
