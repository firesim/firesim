// See LICENSE for license details.

package midas.stage

import firrtl.AnnotationSeq
import firrtl.options.{Dependency, Phase, PhaseManager, PreservesAll, Shell, Stage, StageMain}
import firrtl.options.phases.DeletedWrapper
import firrtl.options.Viewer.view
import firrtl.stage.phases.CatchExceptions

import java.io.{PrintWriter, StringWriter}


class GoldenGatePhase
    extends PhaseManager(
      targets = Seq(
        Dependency[midas.stage.GoldenGateCompilerPhase],
        Dependency[firrtl.stage.phases.WriteEmitted]
      )
    ) {

  override def invalidates(a: Phase) = false

  override val wrappers = Seq(CatchExceptions(_: Phase), DeletedWrapper(_: Phase))
}

class GoldenGateStage extends Stage {
  lazy val phase = new GoldenGatePhase

  override def prerequisites = phase.prerequisites

  override def optionalPrerequisites = phase.optionalPrerequisites

  override def optionalPrerequisiteOf = phase.optionalPrerequisiteOf

  override def invalidates(a: Phase): Boolean = phase.invalidates(a)

  val shell: Shell = new Shell("goldengate") with GoldenGateCli

  def run(annotations: AnnotationSeq): AnnotationSeq = phase.transform(annotations)
}

object GoldenGateMain extends StageMain(new GoldenGateStage)
