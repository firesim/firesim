// See LICENSE for license details.

package midas.stage

import firrtl._
import firrtl.annotations._
import firrtl.options.{Dependency, HasShellOptions, InputAnnotationFileAnnotation, Shell, StageMain, ShellOption}
import firrtl.ir._
import firrtl.stage.{FirrtlCircuitAnnotation, FirrtlStage, RunFirrtlTransformAnnotation}
import logger.LazyLogging
import midas.targetutils.FAMEAnnotation

case class ExtractModuleNameAnnotation(name: String) extends NoTargetAnnotation with FAMEAnnotation
case class RemoveModuleNameAnnotation(name: String) extends NoTargetAnnotation with FAMEAnnotation

trait GoldenGateCli { this: Shell =>
  parser.note("Golden Gate Compiler Options")
  Seq(ConfigPackageAnnotation,
      ConfigStringAnnotation,
      OutputBaseFilenameAnnotation,
      firrtl.stage.FirrtlFileAnnotation,
      firrtl.stage.FirrtlSourceAnnotation,
      firrtl.transforms.NoCircuitDedupAnnotation,
      firrtl.stage.AllowUnrecognizedAnnotations)
    .map(_.addOptions(parser))
}
