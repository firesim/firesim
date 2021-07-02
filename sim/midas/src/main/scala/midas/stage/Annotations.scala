// See LICENSE for license details.

package midas.stage

import firrtl.options.{StageOption, ShellOption, HasShellOptions, CustomFileEmission, Unserializable}
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

// Used to specify the name of the desired runtime configuration
// file.  Will be emitted in the TargetDir
case class RuntimeConfigNameAnnotation(configString: String) extends NoTargetAnnotation with GoldenGateOption

object RuntimeConfigNameAnnotation extends HasShellOptions {

  val options = Seq(
    new ShellOption[String](
      longOption = "golden-gate-runtime-config-name",
      toAnnotationSeq = (a: String) => Seq(RuntimeConfigNameAnnotation(a)),
      helpText = "Specifies the filename for the generated runtime configuration file.",
      shortOption = Some("ggrc"),
      helpValueName = Some("<filename>") ) )
}
