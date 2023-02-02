// See LICENSE for license details.

package midas.stage

import firrtl.AnnotationSeq
import firrtl.options.{Phase, PreservesAll, Shell, Stage, StageMain}
import firrtl.options.phases.DeletedWrapper


class RuntimeConfigGeneratorStage extends Stage with PreservesAll[Phase] {
  val shell: Shell = new Shell("confgen") with RuntimeConfigGeneratorCli

  private val phases: Seq[Phase] =
    Seq(
        midas.stage.Checks,
        new midas.stage.phases.CreateParametersInstancePhase,
        new firrtl.stage.phases.AddDefaults,
        new midas.stage.RuntimeConfigGenerationPhase)
      .map(DeletedWrapper(_))


  def run(annotations: AnnotationSeq): AnnotationSeq = phases.foldLeft(annotations)((a, f) => f.transform(a))
}

object RuntimeConfigGeneratorMain extends StageMain(new RuntimeConfigGeneratorStage)
