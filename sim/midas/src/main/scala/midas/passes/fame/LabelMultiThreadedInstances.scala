// See LICENSE for license details.

package midas.passes.fame

import firrtl._

// This pass labels all instances that are annotated as potential targets for threading as FAME models
object LabelMultiThreadedInstances extends Transform {
  def inputForm = HighForm
  def outputForm = HighForm

  override def execute(state: CircuitState): CircuitState = {
    val p = state.annotations.collectFirst({ case midas.stage.phases.ConfigParametersAnnotation(p)  => p }).get
    val enableMultiThreading = p(midas.EnableModelMultiThreading)
    val fameModelAnnos = new collection.mutable.LinkedHashSet[midas.targetutils.FirrtlFAMEModelAnnotation]
    val updatedAnnos = state.annotations.flatMap {
      case fma: midas.targetutils.FirrtlFAMEModelAnnotation =>
        fameModelAnnos += fma
        None
      case f5a @ midas.targetutils.FirrtlEnableModelMultiThreadingAnnotation(it) =>
        if (enableMultiThreading) {
          fameModelAnnos += midas.targetutils.FirrtlFAMEModelAnnotation(it)
          Some(f5a)
        } else {
          None
        }
      case anno => Some(anno)
    }
    state.copy(annotations = AnnotationSeq(updatedAnnos ++ fameModelAnnos))
  }
}
