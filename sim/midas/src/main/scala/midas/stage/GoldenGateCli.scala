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
case class PartitionIndexAnnotation(n: Int) extends NoTargetAnnotation with FAMEAnnotation
case class PartitionFPGACountAnnotation(n: Int) extends NoTargetAnnotation with FAMEAnnotation

object PartitionFPGACountAnnotation extends HasShellOptions {
  val options: Seq[ShellOption[_]] = Seq(
    new ShellOption[String](
      longOption = "partition-fpga-count",
      shortOption = Some("FPGACNT"),
      toAnnotationSeq = (num: String) => {
        Seq(PartitionFPGACountAnnotation(num.toInt))
      },
      helpText = "partition-fpga-count",
      helpValueName = Some("The number of FPGAs to partition this design")
    )
  )
}

object PartitionIndexAnnotation extends HasShellOptions {
  val options: Seq[ShellOption[_]] = Seq(
    new ShellOption[String](
      longOption = "partition-idx",
      shortOption = Some("PIDX"),
      toAnnotationSeq = (num: String) => {
        Seq(PartitionIndexAnnotation(num.toInt))
      },
      helpText = "extract-module-group-idx",
      helpValueName = Some("The index of the group of modules to extract")
    )
  )
}

trait GoldenGateCli { this: Shell =>
  parser.note("Golden Gate Compiler Options")
  Seq(ConfigPackageAnnotation,
      ConfigStringAnnotation,
      OutputBaseFilenameAnnotation,
      firrtl.stage.FirrtlFileAnnotation,
      firrtl.stage.FirrtlSourceAnnotation,
      firrtl.transforms.NoCircuitDedupAnnotation,
      firrtl.stage.AllowUnrecognizedAnnotations,
      PartitionFPGACountAnnotation,
      PartitionIndexAnnotation
      )
    .map(_.addOptions(parser))
}
