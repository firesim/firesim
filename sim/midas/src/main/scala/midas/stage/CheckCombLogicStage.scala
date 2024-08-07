// See LICENSE for license details.

package midas.stage

import firrtl._
import firrtl.AnnotationSeq
import firrtl.options.{Phase, PreservesAll, Stage}
import firrtl.options.{Dependency, Shell, StageMain}
import firrtl.options.phases.DeletedWrapper
import firrtl.stage.FirrtlCircuitAnnotation
import firrtl.stage.transforms.Compiler
import midas.passes.CheckCombLogicTransforms
import midas.stage.phases.CreateParametersInstancePhase

trait CheckCombLogicCli { this: Shell =>
  parser.note("CheckCombLogic Compiler Options")
  Seq(
    ConfigPackageAnnotation,
    ConfigStringAnnotation,
    firrtl.stage.FirrtlFileAnnotation,
    firrtl.stage.FirrtlSourceAnnotation,
    firrtl.transforms.NoCircuitDedupAnnotation,
    firrtl.stage.AllowUnrecognizedAnnotations,
  )
    .map(_.addOptions(parser))
}

class CheckCombLogicPhase extends Phase {
  override val prerequisites = Seq(
    Dependency(midas.stage.Checks),
    Dependency(midas.stage.AddDerivedAnnotations),
    Dependency[CreateParametersInstancePhase],
  )

  def transform(annotations: AnnotationSeq): AnnotationSeq = {
    val allCircuits = annotations.collect({ case FirrtlCircuitAnnotation(circuit) => circuit })
    require(allCircuits.size == 1, "CheckCombLogic Modules can only process a single Firrtl Circuit at a time.")

    val state = CircuitState(allCircuits.head, firrtl.ChirrtlForm, annotations)

    val combCheck = new Compiler(
      firrtl.stage.Forms.LowForm ++ Seq(Dependency[CheckCombLogicTransforms])
    ).execute(state)

    combCheck.annotations
  }
}

class CheckCombLogicStage extends Stage with PreservesAll[Phase] {
  val shell: Shell = new Shell("combcheck") with CheckCombLogicCli

  private val phases: Seq[Phase] =
    Seq(
      new firrtl.stage.phases.AddDefaults,
      new firrtl.stage.phases.Checks,
      new firrtl.stage.phases.AddCircuit,
      new midas.stage.CheckCombLogicPhase,
    )
      .map(DeletedWrapper(_))

  def run(annotations: AnnotationSeq): AnnotationSeq = phases.foldLeft(annotations)((a, f) => f.transform(a))
}

object CheckCombLogicMain extends StageMain(new CheckCombLogicStage)
