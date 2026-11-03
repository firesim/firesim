// See LICENSE for license details.

package midas.stage

import firrtl.AnnotationSeq
import firrtl.options.{Phase, PreservesAll, Shell, Stage, StageMain}
import firrtl.options.phases.DeletedWrapper

class GoldenGateStage extends Stage with PreservesAll[Phase] {
  val shell: Shell = new Shell("goldengate") with GoldenGateCli

  private val phases: Seq[Phase] =
    Seq(
      new firrtl.options.phases.GetIncludes,
      midas.stage.Checks,
      midas.stage.AddDerivedAnnotations,
      new midas.stage.phases.CreateParametersInstancePhase,
      new firrtl.stage.phases.AddDefaults,
      new firrtl.stage.phases.Checks,
      new midas.stage.phases.ChunkedFirrtlParse, // large .fir inputs: parse per module chunk (ANTLR ~2 GB limit)
      new firrtl.stage.phases.AddCircuit,
      new midas.stage.GoldenGateCompilerPhase,
    )
      .map(DeletedWrapper(_))

  def run(annotations: AnnotationSeq): AnnotationSeq = phases.foldLeft(annotations)((a, f) => f.transform(a))
}

object GoldenGateMain extends StageMain(new GoldenGateStage)
