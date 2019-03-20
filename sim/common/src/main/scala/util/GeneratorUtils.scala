//See LICENSE for license details.

package firesim.util

import java.io.{File, FileWriter}

import chisel3.experimental.RawModule
import chisel3.internal.firrtl.{Port}

import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy.{ValName, LazyModule, AutoBundle}
import freechips.rocketchip.devices.debug.DebugIO
import freechips.rocketchip.util.{HasGeneratorUtilities, ParsedInputNames}

import firesim.configs.DesiredHostFrequency

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

// Can mix this into a Rocket Chip-style generatorApp
trait HasFireSimGeneratorUtilities extends HasTargetAgnosticUtilites {
  lazy val names = generatorArgs.targetNames
  lazy val longName = names.topModuleClass
  // Use a second parsedInputNames to reuse RC's handy config lookup functions
  lazy val hostNames = generatorArgs.platformNames
  lazy val targetParams = getParameters(names.fullConfigClasses)
  lazy val target = getGenerator(names, targetParams)
  // For HasTestSuites
  lazy val testDir = genDir
  val targetTransforms = Seq(
    firesim.passes.AsyncResetRegPass,
    firesim.passes.PlusArgReaderPass
  )
  lazy val hostTransforms = Seq(
    new firesim.passes.ILATopWiringTransform(genDir)
  )

  lazy val hostParams = getHostParameters(names, hostNames)

  def elaborateAndCompileWithMidas() {
    val c3circuit = chisel3.Driver.elaborate(() => target)
    val chirrtl = firrtl.Parser.parse(chisel3.Driver.emit(c3circuit))
    val annos = c3circuit.annotations.map(_.toFirrtl)

    val portList = target.getPorts flatMap {
      // TODO: BOth of these need to be removed before rebar release
      case Port(id: DebugIO, _) => None
      case Port(id: AutoBundle, _) => None
      case otherPort => Some(otherPort.id.instanceName -> otherPort.id)
    }

    generatorArgs.midasFlowKind match {
      case "midas" | "strober" =>
        midas.MidasCompiler(
          chirrtl, annos, portList, genDir, None, targetTransforms, hostTransforms
        )(hostParams alterPartial {case midas.EnableSnapshot => generatorArgs.midasFlowKind == "strober" })
    // Need replay
    }
  }
}

// A runtime-configuration generation for memory models
// Args
//   0: filename (basename)
//   1: Output directory (same as above)
//   Remaining argments are the same as above
object FireSimRuntimeConfGenerator extends App with HasFireSimGeneratorUtilities {
  lazy val generatorArgs = GeneratorArgs(args)
  lazy val genDir = new File(names.targetDir)
  // We need the scala instance of an elaborated memory-model, so that settings
  // may be legalized against the generated hardware. TODO: Currently these
  // settings aren't dependent on the target-AXI4 widths (~bug); this will need
  // to be an optional post-generation step in MIDAS
  lazy val memModel = (hostParams(midas.models.MemModelKey))(hostParams alterPartial {
      case junctions.NastiKey => junctions.NastiParameters(64, 32, 4)})// Related note ^
  chisel3.Driver.elaborate(() => memModel)
  val confFileName = args(0)
  memModel.getSettings(confFileName)(hostParams)
}
