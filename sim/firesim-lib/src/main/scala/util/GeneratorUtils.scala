//See LICENSE for license details.

package firesim.util

import java.io.{File, FileWriter}

import chisel3.{Module, RawModule}
import chisel3.internal.firrtl.Port

import freechips.rocketchip.config.{Config, Parameters}
import freechips.rocketchip.diplomacy.{ValName, LazyModule, AutoBundle}
import freechips.rocketchip.util.{HasGeneratorUtilities, ParsedInputNames}

import freechips.rocketchip.util.property.cover

// Contains FireSim generator utilities that can be reused in MIDAS examples
trait HasTargetAgnosticUtilites extends HasGeneratorUtilities {
  def generatorArgs: firesim.util.GeneratorArgs
  def genDir: File

  def writeOutputFile(fname: String, contents: String): File = {
    val f = new File(genDir, fname)
    val fw = new FileWriter(f)
    fw.write(contents)
    fw.close
    f
  }

  def getGenerator(targetNames: ParsedInputNames, params: Parameters): RawModule = {
    implicit val valName = ValName(targetNames.topModuleClass)
    implicit val p: Parameters = params
    cover.setPropLib(new midas.passes.FireSimPropertyLibrary())
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
    ParsedInputNames(targetDir, topModuleProject, topModuleClass, targetConfigProject, targetConfigs, None)

  def platformNames(): ParsedInputNames =
    ParsedInputNames(targetDir, "Unused", "Unused", platformConfigProject, platformConfigs, None)

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
  def longName: String
  lazy val names = generatorArgs.targetNames
  // Use a second parsedInputNames to reuse RC's handy config lookup functions
  lazy val hostNames = generatorArgs.platformNames
  lazy val targetParams = getParameters(names.fullConfigClasses)
  lazy val target = getGenerator(names, targetParams)
  // For HasTestSuites
  lazy val testDir = genDir

  def elaborate() {
    val c3circuit = chisel3.Driver.elaborate(() => target)
    val annos = c3circuit.annotations.map(_.toFirrtl)
    chisel3.Driver.dumpFirrtl(c3circuit, Some(new File(genDir, s"$longName.fir"))) // FIRRTL
    val annotationFile = new File(genDir, s"$longName.anno.json")
    val af = new FileWriter(annotationFile)
    af.write(midas.passes.fame.JsonProtocol.serialize(c3circuit.annotations.map(_.toFirrtl)))
    af.close()
  }
}
