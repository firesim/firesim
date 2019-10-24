// See LICENSE for license details.

package midas.stage

import freechips.rocketchip.config.{Parameters, Config}
import freechips.rocketchip.util.{ParsedInputNames}

trait ConfigLookup {
  // This copies the rocketChip get config code, but adds support for looking up a config class
  // from one of many packages
  def getConfigWithFallback(packages: Seq[String], configNames: Seq[String]): Config = {
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

  // For host configurations, look up configs in one of three places:
  // 1) The user specified project (eg. firesim.firesim)
  // 2) firesim.configs -> Legacy SimConfigs
  // 3) firesim.util  -> this has a bunch of target agnostic configurations, like host frequency
  // 4) midas -> This has debug features, etc
  // Allows the user to concatenate configs together from different packages
  // without needing to fully specify the class name for each config
  // eg. FireSimConfig_F90MHz maps to: firesim.util.F90MHz ++ firesim.firesim.FiresimConfig
  def getParameters(hostNames: ParsedInputNames): Parameters = {
    val packages = (hostNames.configProject +: Seq("firesim.configs", "firesim.util", "midas")).distinct
    new Config(getConfigWithFallback(packages, hostNames.configClasses))
  }
}
