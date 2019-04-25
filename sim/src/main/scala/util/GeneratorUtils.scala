//See LICENSE for license details.

package firesim.util

import java.io.{File, FileWriter}

import chisel3.experimental.RawModule

import freechips.rocketchip.config.{Field, Config, Parameters}
import freechips.rocketchip.diplomacy.{ValName, LazyModule}
import freechips.rocketchip.util.{HasGeneratorUtilities, ParsedInputNames}

object HostFPGAConfigs {
  object DesiredHostFrequency extends Field[Int](190) // In MHz
  object BuildStrategy extends Field[BuildStrategies.IsBuildStrategy](BuildStrategies.Timing)

  class WithDesiredHostFrequency(freq: Int) extends Config((site, here, up) => {
      case DesiredHostFrequency => freq
  })

  object BuildStrategies {
    trait IsBuildStrategy {
      def flowString: String
      def emitTcl =  "set strategy \"" + flowString + "\"\n"
    }
    object Basic extends IsBuildStrategy { val flowString = "BASIC" }
    // This is the default strategy AWS sets in "aws_build_dcp_from_cl.sh"
    object Timing extends IsBuildStrategy { val flowString = "TIMING" }
    object Explore extends IsBuildStrategy { val flowString = "EXPLORE" }
    object Congestion extends IsBuildStrategy { val flowString = "CONGESTION" }
    // This is the strategy AWS uses if you give it a bogus strategy string
    object Default extends IsBuildStrategy { val flowString = "DEFAULT" }
  }

  // Overrides the AWS default strategy with a desired one
  class WithBuildStategy(strategy: BuildStrategies.IsBuildStrategy) extends Config((site, here, up) => {
    case BuildStrategy => strategy
  })

  class F160MHz extends WithDesiredHostFrequency(160)
  class F150MHz extends WithDesiredHostFrequency(150)
  class F135MHz extends WithDesiredHostFrequency(135)
  class F100MHz extends WithDesiredHostFrequency(100)
  class  F90MHz extends WithDesiredHostFrequency(90)
  class  F85MHz extends WithDesiredHostFrequency(85)
  class  F80MHz extends WithDesiredHostFrequency(80)
  class  F70MHz extends WithDesiredHostFrequency(70)
  class  F65MHz extends WithDesiredHostFrequency(65)
  class  F60MHz extends WithDesiredHostFrequency(60)
  class  F50MHz extends WithDesiredHostFrequency(50)

  class Congestion extends WithBuildStategy(BuildStrategies.Congestion)
}

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
    val requestedFrequency = hostParams(HostFPGAConfigs.DesiredHostFrequency)
    val buildStrategy      = hostParams(HostFPGAConfigs.BuildStrategy)
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
