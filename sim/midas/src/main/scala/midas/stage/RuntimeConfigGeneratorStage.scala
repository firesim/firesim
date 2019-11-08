// See LICENSE for license details.

package midas.stage

import firrtl.AnnotationSeq
import firrtl.options.{Phase, PhaseManager, PreservesAll, Shell, Stage, StageMain}
import firrtl.options.phases.DeletedWrapper
import firrtl.options.Viewer.view

import java.io.{StringWriter, PrintWriter}

class RuntimeConfigGeneratorStage extends Stage with PreservesAll[Phase] {
  val shell: Shell = new Shell("confgen") with RuntimeConfigGeneratorCli

  private val phases: Seq[Phase] =
    Seq(
        new GoldenGateGetIncludes,
        new firrtl.stage.phases.AddDefaults,
        new midas.stage.RuntimeConfigGenerationPhase)
      .map(DeletedWrapper(_))


  def run(annotations: AnnotationSeq): AnnotationSeq = phases.foldLeft(annotations)((a, f) => f.transform(a))
}

object RuntimeConfigGeneratorMain extends StageMain(new RuntimeConfigGeneratorStage)
