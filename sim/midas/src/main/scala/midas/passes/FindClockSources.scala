// See LICENSE for license details.

package midas.passes

import firrtl._
import firrtl.annotations._

import midas.passes.fame.RTRenamer

case class FindClockSourceAnnotation(
    target: ReferenceTarget,
    originalTarget: Option[ReferenceTarget] = None) extends Annotation {
  //require(target.module == target.circuit, s"Queried leaf clock ${target} must provide an absolute instance path")
  def update(renames: RenameMap): Seq[FindClockSourceAnnotation] =
    Seq(this.copy(RTRenamer.exact(renames)(target), originalTarget.orElse(Some(target))))
}

case class ClockSourceAnnotation(queryTarget: ReferenceTarget, source: Option[ReferenceTarget]) extends Annotation {
  def update(renames: RenameMap): Seq[ClockSourceAnnotation] =
    Seq(this.copy(queryTarget, source.map(s => RTRenamer.exact(renames)(s))))
}

object FindClockSources extends firrtl.Transform {
  def inputForm = LowForm
  def outputForm = LowForm

  def execute(state: CircuitState): CircuitState = {
    val queryAnnotations = state.annotations.collect({ case anno: FindClockSourceAnnotation => anno })
    queryAnnotations foreach { case FindClockSourceAnnotation(target, _) =>
      require(target.module == target.circuit, s"Queried leaf clock ${target} must provide an absolute instance path")
    }
    val sourceFinder = new ClockSourceFinder(state)
    val sourceMappings = queryAnnotations.map(qA => qA.target -> sourceFinder.findRootDriver(qA.target)).toMap
    val clockSourceAnnotations = queryAnnotations.map(qAnno =>
      ClockSourceAnnotation(qAnno.originalTarget.getOrElse(qAnno.target), sourceMappings(qAnno.target)))
    val prunedAnnos = state.annotations.filterNot(queryAnnotations.toSet)
    state.copy(annotations = clockSourceAnnotations ++ prunedAnnos)
  }
}
