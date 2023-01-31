// See LICENSE for license details.

package midas.stage

import firrtl.options.{ShellOption, HasShellOptions, Unserializable}
import firrtl.annotations.{NoTargetAnnotation, Annotation}

// Prevent configuration annotations from propagating out.
sealed trait GoldenGateOption extends Unserializable { this: Annotation => }

case class ConfigPackageAnnotation(packageName: String) extends NoTargetAnnotation with GoldenGateOption

object ConfigPackageAnnotation extends HasShellOptions {

  val options = Seq(
    new ShellOption[String](
      longOption = "golden-gate-config-package",
      toAnnotationSeq = (a: String) => Seq(ConfigPackageAnnotation(a)),
      helpText = "Specifies the package in which the compiler config can be found.",
      shortOption = Some("ggcp"),
      helpValueName = Some("<scala package>") ) )
}

case class ConfigStringAnnotation(configString: String) extends NoTargetAnnotation with GoldenGateOption

object ConfigStringAnnotation extends HasShellOptions {

  val options = Seq(
    new ShellOption[String](
      longOption = "golden-gate-config-string",
      toAnnotationSeq = (a: String) => Seq(ConfigStringAnnotation(a)),
      helpText = "Specifies a config string. Class names may be appended using '_' to generate compound configs.",
      shortOption = Some("ggcs"),
      helpValueName = Some("<class name>{[_<additional class names>]}}") ) )
}

case class OutputBaseFilenameAnnotation(name: String) extends NoTargetAnnotation with GoldenGateOption

object OutputBaseFilenameAnnotation extends HasShellOptions {
  val options = Seq(
    new ShellOption[String](
      longOption = "output-filename-base",
      toAnnotationSeq = (a: String) => Seq(OutputBaseFilenameAnnotation(a)),
      helpText = "Specifies the base (prefix) used on Golden Gate generated files.",
      shortOption = Some("ofb"),
      helpValueName = Some("<output-filename-base>") ) )
}

