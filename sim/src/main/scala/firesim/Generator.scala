package firesim.firesim

import java.io.{File, FileWriter}

import chisel3.experimental.RawModule
import chisel3.internal.firrtl.{Circuit, Port}

import freechips.rocketchip.diplomacy._
import freechips.rocketchip.devices.debug.DebugIO
import freechips.rocketchip.util.{HasGeneratorUtilities, ParsedInputNames}
import freechips.rocketchip.system.DefaultTestSuites._
import freechips.rocketchip.system.{TestGeneration, RegressionTestSuite}
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.subsystem.RocketTilesKey
import freechips.rocketchip.tile.XLen

import boom.system.{BoomTilesKey, BoomTestSuites}

case class FireSimGeneratorArgs(
  midasFlowKind: String = "midas", // "midas", "strober", "replay"
  targetDir: String, // Where generated files should be emitted
  topModuleProject: String = "firesim.firesim",
  topModuleClass: String,
  targetConfigProject: String = "firesim.firesim",
  targetConfigs: String,
  platformConfigProject: String = "firesim.firesim",
  platformConfigs: String) {

  def targetNames(): ParsedInputNames =
    ParsedInputNames(targetDir, topModuleProject, topModuleClass, targetConfigProject, targetConfigs)

  def platformNames(): ParsedInputNames =
    ParsedInputNames(targetDir, "Unused", "Unused", platformConfigProject, platformConfigs)

  def tupleName(): String = s"$topModuleClass-$targetConfigs-$platformConfigs"
}

object FireSimGeneratorArgs {
  def apply(a: Seq[String]): FireSimGeneratorArgs = {
    require(a.size == 8, "Usage: sbt> run [midas | strober | replay] " +
      "TargetDir TopModuleProjectName TopModuleName ConfigProjectName ConfigNameString HostConfig")
    FireSimGeneratorArgs(a(0), a(1), a(2), a(3), a(4), a(5), a(6), a(7))
  }

  // Shortform useful when all classes are local to the firesim.firesim package
  def apply(targetName: String, targetConfig: String, platformConfig: String): FireSimGeneratorArgs =
  FireSimGeneratorArgs(
    targetDir = "generated-src/",
    topModuleClass = targetName,
    targetConfigs = targetConfig,
    platformConfigs = platformConfig
  )
}

trait HasFireSimGeneratorUtilities extends HasGeneratorUtilities with HasTestSuites {
  // We reuse this trait in the scala tests and in a top-level App, where this
  // this structure will be populated with CML arguments
  def generatorArgs: FireSimGeneratorArgs

  def getGenerator(targetNames: ParsedInputNames, params: Parameters): RawModule = {
    implicit val valName = ValName(targetNames.topModuleClass)
    targetNames.topModuleClass match {
      case "FireSim"  => LazyModule(new FireSim()(params)).module
      case "FireBoom" => LazyModule(new FireBoom()(params)).module
      case "FireSimNoNIC"  => LazyModule(new FireSimNoNIC()(params)).module
      case "FireBoomNoNIC" => LazyModule(new FireBoomNoNIC()(params)).module
    }
  }

  lazy val names = generatorArgs.targetNames
  lazy val longName = names.topModuleClass
  // Use a second parsedInputNames to reuse RC's handy config lookup functions
  lazy val hostNames = generatorArgs.platformNames
  lazy val targetParams = getParameters(names.fullConfigClasses)
  lazy val target = getGenerator(names, targetParams)
  lazy val testDir = new File(names.targetDir)
  val targetTransforms = Seq(
    firesim.passes.AsyncResetRegPass,
    firesim.passes.PlusArgReaderPass
  )
  lazy val hostTransforms = Seq(
    new firesim.passes.ILATopWiringTransform(testDir)
  )

  // While this is called the HostConfig, it does also include configurations
  // that control what models are instantiated
  lazy val hostParams = getParameters(
    hostNames.fullConfigClasses ++
    names.fullConfigClasses
  ).alterPartial({ case midas.OutputDir => testDir })

  def elaborateAndCompileWithMidas() {
    val c3circuit = chisel3.Driver.elaborate(() => target)
    val chirrtl = firrtl.Parser.parse(chisel3.Driver.emit(c3circuit))
    val annos = c3circuit.annotations.map(_.toFirrtl)

    val portList = target.getPorts flatMap {
      case Port(id: DebugIO, _) => None
      case Port(id: AutoBundle, _) => None // What the hell is AutoBundle?
      case otherPort => Some(otherPort.id.instanceName -> otherPort.id)
    }

    generatorArgs.midasFlowKind match {
      case "midas" | "strober" =>
        midas.MidasCompiler(
          chirrtl, annos, portList, testDir, None, targetTransforms, hostTransforms
        )(hostParams alterPartial {case midas.EnableSnapshot => generatorArgs.midasFlowKind == "strober" })
    // Need replay
    }
  }

  /** Output software test Makefrags, which provide targets for integration testing. */
  def generateTestSuiteMakefrags {
    addTestSuites(names.topModuleClass, targetParams)
    writeOutputFile(s"$longName.d", TestGeneration.generateMakefrag) // Subsystem-specific test suites
  }

  def writeOutputFile(fname: String, contents: String): File = {
    val f = new File(testDir, fname)
    val fw = new FileWriter(f)
    fw.write(contents)
    fw.close
    f
  }
}

trait HasTestSuites {
  val rv64RegrTestNames = collection.mutable.LinkedHashSet(
      "rv64ud-v-fcvt",
      "rv64ud-p-fdiv",
      "rv64ud-v-fadd",
      "rv64uf-v-fadd",
      "rv64um-v-mul",
      // "rv64mi-p-breakpoint", // Not implemented in BOOM
      // "rv64uc-v-rvc", // Not implemented in BOOM
      "rv64ud-v-structural",
      "rv64si-p-wfi",
      "rv64um-v-divw",
      "rv64ua-v-lrsc",
      "rv64ui-v-fence_i",
      "rv64ud-v-fcvt_w",
      "rv64uf-v-fmin",
      "rv64ui-v-sb",
      "rv64ua-v-amomax_d",
      "rv64ud-v-move",
      "rv64ud-v-fclass",
      "rv64ua-v-amoand_d",
      "rv64ua-v-amoxor_d",
      "rv64si-p-sbreak",
      "rv64ud-v-fmadd",
      "rv64uf-v-ldst",
      "rv64um-v-mulh",
      "rv64si-p-dirty")

  val rv32RegrTestNames = collection.mutable.LinkedHashSet(
      "rv32mi-p-ma_addr",
      "rv32mi-p-csr",
      "rv32ui-p-sh",
      "rv32ui-p-lh",
      "rv32uc-p-rvc",
      "rv32mi-p-sbreak",
      "rv32ui-p-sll")

  def addTestSuites(targetName: String, params: Parameters) {
    val coreParams =
      if (params(RocketTilesKey).nonEmpty) {
        params(RocketTilesKey).head.core
      } else {
        params(BoomTilesKey).head.core
      }
    val xlen = params(XLen)
    val vm = coreParams.useVM
    val env = if (vm) List("p","v") else List("p")
    coreParams.fpu foreach { case cfg =>
      if (xlen == 32) {
        TestGeneration.addSuites(env.map(rv32uf))
        if (cfg.fLen >= 64)
          TestGeneration.addSuites(env.map(rv32ud))
      } else {
        TestGeneration.addSuite(rv32udBenchmarks)
        TestGeneration.addSuites(env.map(rv64uf))
        if (cfg.fLen >= 64)
          TestGeneration.addSuites(env.map(rv64ud))
      }
    }
    if (coreParams.useAtomics)    TestGeneration.addSuites(env.map(if (xlen == 64) rv64ua else rv32ua))
    if (coreParams.useCompressed) TestGeneration.addSuites(env.map(if (xlen == 64) rv64uc else rv32uc))
    val (rvi, rvu) =
      if (params(BoomTilesKey).nonEmpty) ((if (vm) BoomTestSuites.rv64i else BoomTestSuites.rv64pi), rv64u)
      else if (xlen == 64) ((if (vm) rv64i else rv64pi), rv64u)
      else            ((if (vm) rv32i else rv32pi), rv32u)

    TestGeneration.addSuites(rvi.map(_("p")))
    TestGeneration.addSuites((if (vm) List("v") else List()).flatMap(env => rvu.map(_(env))))
    TestGeneration.addSuite(benchmarks)
    TestGeneration.addSuite(new RegressionTestSuite(if (xlen == 64) rv64RegrTestNames else rv32RegrTestNames))
    TestGeneration.addSuite(FastBlockdevTests)
    TestGeneration.addSuite(SlowBlockdevTests)
    if (!targetName.contains("NoNIC"))
      TestGeneration.addSuite(NICLoopbackTests)
  }
}

object FireSimGenerator extends App with HasFireSimGeneratorUtilities {
  lazy val generatorArgs = FireSimGeneratorArgs(args)

  elaborateAndCompileWithMidas
  generateTestSuiteMakefrags
}

// A runtime-configuration generation for memory models
// Args
//   0: filename (basename)
//   1: Output directory (same as above)
//   Remaining argments are the same as above
object FireSimRuntimeConfGenerator extends App with HasFireSimGeneratorUtilities {
  lazy val generatorArgs = FireSimGeneratorArgs(args)
  // We need the scala instance of an elaborated memory-model, so that settings
  // may be legalized against the generated hardware. TODO: Currently these
  // settings aren't dependent on the target-AXI4 widths (~bug); this will need
  // to be an optional post-generation step in MIDAS
  lazy val memModel = (hostParams(midas.MemModelKey).get)(hostParams alterPartial {
      case junctions.NastiKey => junctions.NastiParameters(64, 32, 4)})// Related note ^
  chisel3.Driver.elaborate(() => memModel)

  val confFileName = args(0)
  memModel match {
    case model: midas.models.MidasMemModel => {
      model.getSettings(confFileName)(hostParams)
    }
    // TODO: Support other model types;
    case _ => throw new RuntimeException(
      "This memory model does not support runtime-configuration generation")
  }
}
