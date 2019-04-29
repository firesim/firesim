//See LICENSE for license details.

package firesim.util

import java.io.{File, FileWriter}

import chisel3.experimental.RawModule

import freechips.rocketchip.config.{Config, Parameters}
import freechips.rocketchip.diplomacy.{ValName, LazyModule}
import freechips.rocketchip.util.{HasGeneratorUtilities, ParsedInputNames}

// Contains FireSim generator utilities that can be reused in MIDAS examples
trait HasTargetAgnosticUtilites extends HasGeneratorUtilities {
  def generatorArgs: firesim.util.GeneratorArgs
  def hostParams: Parameters
  def genDir: File

  def writeOutputFile(fname: String, contents: String): File = {
    val f = new File(genDir, fname)
    val fw = new FileWriter(f)
    fw.write(contents)
    fw.close
    f
  }

  // Capture FPGA-toolflow related verilog defines
  def generateHostVerilogHeader() {
    val headerName = "cl_firesim_generated_defines.vh"
    writeOutputFile(headerName, s"\n")
  }

  // Emit TCL variables to control the FPGA compilation flow
  def generateTclEnvFile() {
    val headerName = "cl_firesim_generated_env.tcl"
    val requestedFrequency = hostParams(DesiredHostFrequency)
    val buildStrategy      = hostParams(BuildStrategy)
    val constraints = s"""# FireSim Generated Environment Variables
set desired_host_frequency ${requestedFrequency}
${buildStrategy.emitTcl}
"""
    writeOutputFile(headerName, constraints)
  }

  def getGenerator(targetNames: ParsedInputNames, params: Parameters): RawModule = {
    implicit val valName = ValName(targetNames.topModuleClass)
    implicit val p: Parameters = params
    val cls = Class.forName(targetNames.fullTopModuleClass)
    val inst = try {
      // Check if theres a constructor that accepts a Parameters object
      cls.getConstructor(classOf[Parameters]).newInstance(params)
    } catch {
      // Otherwise try to fallback on an argument-less constructor
      case e: java.lang.NoSuchMethodException => cls.getConstructor().newInstance()
    }
    inst match {
      case m: RawModule => m
      case l: LazyModule => LazyModule(l).module
    }
  }

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
          case _: Throwable => getConfig(oremainingPackages, configName)
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
  // 2) firesim.util  -> this has a bunch of target agnostic configurations, like host frequency
  // 3) midas -> This has debug features, etc
  // Allows the user to concatenate configs together from different packages
  // without needing to fully specify the class name for each config
  // eg. FireSimConfig_F90MHz maps to: firesim.util.F90MHz ++ firesim.firesim.FiresimConfig
  def getHostParameters(targetNames: ParsedInputNames, hostNames: ParsedInputNames): Parameters = {
    val packages = hostNames.configProject +: Seq("firesim.util", "midas")
    val hParams = new Config(
      getConfigWithFallback(packages, hostNames.configClasses) ++
      getConfig(targetNames.fullConfigClasses)).toInstance

    hParams.alterPartial({ case midas.OutputDir => genDir })
  }
}

case class GeneratorArgs(
  midasFlowKind: String, // "midas", "strober", "replay"
  targetDir: String, // Where generated files should be emitted
  topModuleProject: String,
  topModuleClass: String,
  targetConfigProject: String,
  targetConfigs: String,
  platformConfigProject: String,
  platformConfigs: String) {

  def targetNames(): ParsedInputNames =
    ParsedInputNames(targetDir, topModuleProject, topModuleClass, targetConfigProject, targetConfigs)

  def platformNames(): ParsedInputNames =
    ParsedInputNames(targetDir, "Unused", "Unused", platformConfigProject, platformConfigs)

  def tupleName(): String = s"$topModuleClass-$targetConfigs-$platformConfigs"
}

// Companion object to build the GeneratorArgs from the args passed to App
object GeneratorArgs {
  def apply(a: Seq[String]): GeneratorArgs = {
    require(a.size == 8, "Usage: sbt> run [midas | strober | replay] " +
      "TargetDir TopModuleProjectName TopModuleName ConfigProjectName ConfigNameString HostConfig")
    GeneratorArgs(a(0), a(1), a(2), a(3), a(4), a(5), a(6), a(7))
  }
}
