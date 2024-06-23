// See LICENSE for license details.

package midas.stage

import firrtl._
import firrtl.AnnotationSeq
import firrtl.options.{Phase, PreservesAll, Shell, Stage, StageMain}
import firrtl.options.{Dependency, HasShellOptions, InputAnnotationFileAnnotation, Shell, StageMain, ShellOption}
import firrtl.options.phases.DeletedWrapper
import firrtl.annotations._
import firrtl.ir._
import firrtl.stage.{FirrtlCircuitAnnotation, FirrtlStage, RunFirrtlTransformAnnotation}
import firrtl.stage.transforms.Compiler
import logger.LazyLogging
import midas.passes.SplitModulesByPortTransforms
import midas.stage.phases.{CreateParametersInstancePhase, ConfigParametersAnnotation}


trait SplitModulesByPortCli { this: Shell =>
  parser.note("SplitModulesByPort Compiler Options")
  Seq(ConfigPackageAnnotation,
      ConfigStringAnnotation,
      firrtl.stage.FirrtlFileAnnotation,
      firrtl.stage.FirrtlSourceAnnotation,
      firrtl.transforms.NoCircuitDedupAnnotation,
      firrtl.stage.AllowUnrecognizedAnnotations
      )
    .map(_.addOptions(parser))
}


class SplitModulesByPortPhase extends Phase {
  override val prerequisites = Seq(
    Dependency(midas.stage.Checks),
    Dependency(midas.stage.AddDerivedAnnotations),
    Dependency[CreateParametersInstancePhase])

  def transform(annotations: AnnotationSeq): AnnotationSeq = {
    val allCircuits = annotations.collect({ case FirrtlCircuitAnnotation(circuit) => circuit })
    require(allCircuits.size == 1, "Split Modules can only process a single Firrtl Circuit at a time.")

    val state = CircuitState(allCircuits.head, firrtl.ChirrtlForm, annotations)

    val split = new Compiler(
      firrtl.stage.Forms.LowForm ++ Seq(Dependency[SplitModulesByPortTransforms])
    ).execute(state)

    split.annotations
  }
}



class SplitModulesByPortStage extends Stage with PreservesAll[Phase] {
  val shell: Shell = new Shell("splitmodules") with SplitModulesByPortCli

  private val phases: Seq[Phase] =
    Seq(
        new firrtl.stage.phases.AddDefaults,
        new firrtl.stage.phases.Checks,
        new firrtl.stage.phases.AddCircuit,
        new midas.stage.SplitModulesByPortPhase
      )
      .map(DeletedWrapper(_))

  def run(annotations: AnnotationSeq): AnnotationSeq = phases.foldLeft(annotations)((a, f) => f.transform(a))
}

object SplitModulesByPortMain extends StageMain(new SplitModulesByPortStage)
