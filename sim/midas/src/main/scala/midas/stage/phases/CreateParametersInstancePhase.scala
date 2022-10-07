// See LICENSE for license details.

package midas.stage.phases

import midas.OutputDir
import midas.stage.{ConfigPackageAnnotation, ConfigStringAnnotation}
import firrtl.AnnotationSeq
import firrtl.options.{Phase, PreservesAll, Unserializable, TargetDirAnnotation, Dependency}
import firrtl.annotations.NoTargetAnnotation
import freechips.rocketchip.config.{Parameters, Config}

import java.io.{File}

case class ConfigParametersAnnotation(p: Parameters) extends NoTargetAnnotation with Unserializable

object CreateParametersFromConfigString {
  /**
    * This copies the rocketChip get config code, but adds support for looking up a config class
    * from one of many packages
    */
  private def getConfigWithFallback(packages: Seq[String], configNames: Seq[String]): Config = {
    // Recursively try to lookup config in a set of scala packages
    def getConfig(remainingPackages: Seq[String], configName: String): Config = remainingPackages match {
      // No fallback packages left
      case Nil => throw new Exception(
        s"""Unable to find class "$configName" in packages: "${packages.mkString(", ")}", did you misspell it?""")
      // Take the head of the package list, and check if there is a class with the matching name
      case configPackage :: oremainingPackages => {
        try {
          Class.forName(configPackage + "." + configName).newInstance.asInstanceOf[Config]
        } catch {
          case t: java.lang.ClassNotFoundException => getConfig(oremainingPackages, configName)
        }
      }
    }
    // For each config basename, look up the correct class from one of a
    // sequence of potential packages and concatenate them together to create
    // a complete parameterization space
    new Config(configNames.foldRight(Parameters.empty) { case (currentName, config) =>
      getConfig(packages, currentName) ++ config
    })
  }

  /**
    * Returns the parameters associated with a configuration.
    *
    * For host configurations, look up configs in one of three places:
    * 1) The user specified project (eg. firesim.firesim)
    * 2) firesim.configs -> Legacy SimConfigs
    * 3) firesim.util  -> this has a bunch of target agnostic configurations, like host frequency
    * 4) midas -> This has debug features, etc
    */
  def apply(configProject: String, configClasses: Seq[String]): Parameters = {
    val packages = (configProject +: Seq("firesim.configs", "firesim.util", "midas")).distinct
    new Config(getConfigWithFallback(packages, configClasses))
  }
}

class CreateParametersInstancePhase extends Phase with PreservesAll[Phase] {
  override val prerequisites = Seq(Dependency(midas.stage.Checks))

  override def transform(annotations: AnnotationSeq): AnnotationSeq = {
    val configPackage = annotations.collectFirst({ case ConfigPackageAnnotation(p) => p }).get
    val configClasses = annotations.collectFirst({ case ConfigStringAnnotation(s) => s }).get.split('_')
    val targetDir = annotations.collectFirst({ case TargetDirAnnotation(t) => new File(t) }).get

    // The output directory is specified on the command line; put it in the
    // parameters object so it is visible to chisel elaborations.
    val p = CreateParametersFromConfigString(configPackage, configClasses).alterPartial({ case OutputDir => targetDir })
    ConfigParametersAnnotation(p) +: annotations
  }
}
