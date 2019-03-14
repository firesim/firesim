//See LICENSE for license details.

package firesim.util

import java.io.{File, FileWriter}

import chisel3.experimental.RawModule

import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy.{ValName, LazyModule}
import freechips.rocketchip.util.{HasGeneratorUtilities, ParsedInputNames}

import firesim.firesim.DesiredHostFrequency

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
    val requestedFrequency = hostParams(DesiredHostFrequency)
    val availableFrequenciesMhz = Seq(190, 175, 160, 90, 85, 75)
    if (!availableFrequenciesMhz.contains(requestedFrequency)) {
      throw new RuntimeException(s"Requested frequency (${requestedFrequency} MHz) is not available.\nAllowed options: ${availableFrequenciesMhz} MHz")
    }
    writeOutputFile(headerName, s"`define SELECTED_FIRESIM_CLOCK ${requestedFrequency}\n")
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

  // While this is called the HostConfig, it does also include configurations
  // that control what models are instantiated
  def getHostParameters(targetNames: ParsedInputNames, hostNames: ParsedInputNames): Parameters =
    getParameters(
      hostNames.fullConfigClasses ++
      targetNames.fullConfigClasses
    ).alterPartial({ case midas.OutputDir => genDir })
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
