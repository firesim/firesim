package firesim

import chisel3.experimental.RawModule
import chisel3.internal.firrtl.{Circuit, Port}
import freechips.rocketchip.diplomacy._
// import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.devices.debug.DebugIO
import freechips.rocketchip.util.{GeneratorApp, ParsedInputNames}
import freechips.rocketchip.system.DefaultTestSuites._
import freechips.rocketchip.system.{TestGeneration, RegressionTestSuite}
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.subsystem.RocketTilesKey
import freechips.rocketchip.tile.XLen
import boom.system.{BoomTilesKey, BoomTestSuites}
import java.io.File

trait HasGenerator extends GeneratorApp {
  def getGenerator(targetNames: ParsedInputNames, params: Parameters): RawModule = {
    implicit val valName = ValName(targetNames.topModuleClass)
    targetNames.topModuleClass match {
      case "FireSim"  => LazyModule(new FireSim()(params)).module
      case "FireBoom" => LazyModule(new FireBoom()(params)).module
      case "FireSimNoNIC"  => LazyModule(new FireSimNoNIC()(params)).module
      case "FireBoomNoNIC" => LazyModule(new FireBoomNoNIC()(params)).module
    }
  }

  override lazy val names: ParsedInputNames = {
    require(args.size == 8, "Usage: sbt> run [midas | strober | replay] " +
      "TargetDir TopModuleProjectName TopModuleName ConfigProjectName ConfigNameString HostConfig")
    ParsedInputNames(
      targetDir = args(1),
      topModuleProject = args(2),
      topModuleClass = args(3),
      configProject = args(4),
      configs = args(5))
  }

  // Unfortunately ParsedInputNames is the interface provided by RC's convenient 
  // parameter elaboration utilities
  lazy val hostNames: ParsedInputNames = ParsedInputNames(
      targetDir = args(1),
      topModuleProject = "Unused",
      topModuleClass = "Unused",
      configProject = args(6),
      configs = args(7))

  lazy val targetParams = getParameters(names.fullConfigClasses)
  lazy val targetGenerator = getGenerator(names, targetParams)
  lazy val testDir = new File(names.targetDir)
  // While this is called the HostConfig, it does also include configurations
  // that control what models are instantiated
  lazy val hostParams = getParameters(hostNames.fullConfigClasses ++ names.fullConfigClasses)
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

  def addTestSuites(params: Parameters) {
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
  }
}

object FireSimGenerator extends HasGenerator with HasTestSuites {
  val longName = names.topModuleProject
  val libFile = if (args.size > 8) Some(new File(args(8))) else None
  // To leave the debug module unconnected we omit it from the Record we generate
  // before invoking the MIDAS compiler.
  lazy val target = targetGenerator
  val c3circuit = chisel3.Driver.elaborate(() => target)
  val chirrtl = firrtl.Parser.parse(chisel3.Driver.emit(c3circuit))
  val annos = c3circuit.annotations.map(_.toFirrtl)
  val portList = target.getPorts flatMap {
    case Port(id: DebugIO, _) => None
    case Port(id: AutoBundle, _) => None // What the hell is AutoBundle?
    case otherPort => Some(otherPort.id)
  }
  override def addTestSuites = super.addTestSuites(params)
  val customPasses = Seq(
    passes.AsyncResetRegPass,
    passes.PlusArgReaderPass
  )

  args.head match {
    case "midas" | "strober"  =>
      midas.MidasCompiler(chirrtl, annos, portList, testDir, libFile, customPasses)(hostParams alterPartial { 
        case midas.EnableSnapshot => args.head == "strober" })
    // Need replay
  }

  generateTestSuiteMakefrags
}

// A runtime-configuration generation for memory models
// Args
//   0: filename (basename)
//   1: Output directory (same as above)
//   Remaining argments are the same as above
object FireSimRuntimeConfGenerator extends HasGenerator {
  val longName = ""
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
