package firesim.picorv32

import java.io.{File, FileWriter}
import scala.io.Source

import chisel3.experimental.RawModule
import chisel3.internal.firrtl.{Circuit, Port, Width}
import chisel3.core._
import chisel3.experimental.MultiIOModule

import freechips.rocketchip.diplomacy._
import freechips.rocketchip.devices.debug.DebugIO
import freechips.rocketchip.util.{HasGeneratorUtilities, ParsedInputNames}
import freechips.rocketchip.system.DefaultTestSuites._
import freechips.rocketchip.system.{TestGeneration, RegressionTestSuite}
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.subsystem.RocketTilesKey
import freechips.rocketchip.tile.XLen
import freechips.rocketchip.config.Config
import freechips.rocketchip.rocket.{RocketCoreParams, MulDivParams}

import boom.system.{BoomTilesKey, BoomTestSuites}

import sifive.blocks.devices.uart.UARTPortIO

import firrtl.annotations.Annotation

trait FireSimGeneratorUtils extends HasTestSuites {
  val targetTransforms = Seq(
    // firesim.passes.AsyncResetRegPass,
    // firesim.passes.PlusArgReaderPass
  )

  lazy val hostTransforms = Seq(
    new firesim.passes.ILATopWiringTransform(testDir)
  )

  val targetDir = "/home/centos/firesim/sim/generated-src/f1/PicoRV32-PicoRV32Config-FireSimConfig/"
  lazy val testDir = new File(targetDir)

  def midasParams = (new Config(new firesim.firesim.PicoRV32Config)).toInstance

  def elaborateAndCompileWithMidas {
    val lines = Source.fromFile("src/main/scala/picorv32/synth.fir").getLines()
    val chirrtl = firrtl.Parser.parse(lines)

    val dut = chisel3.Driver.elaborate(() => new UARTWrapper)
    val annos = dut.annotations.map(_.toFirrtl)
    val portList = dut.components.find(_.name == "UARTWrapper").get.ports.flatMap(p => Some(p.id))

    midas.MidasCompiler(
      chirrtl, annos, portList, testDir, None, targetTransforms, hostTransforms
      )(midasParams)
    // Need replay
  }

  def generateTestSuiteMakefrags {
    addTestSuites
    writeOutputFile("PicoRV32.d", TestGeneration.generateMakefrag) // Subsystem-specific test suites
  }

  def writeOutputFile(fname: String, contents: String): File = {
    val f = new File(testDir, fname)
    val fw = new FileWriter(f)
    fw.write(contents)
    fw.close
    f
  }
}

object FireSimGenerator extends App with FireSimGeneratorUtils {
  require (args.size == 1, "Command line arg must be output directory!")
  elaborateAndCompileWithMidas
  generateTestSuiteMakefrags
}

class UARTWrapper extends MultiIOModule {
        val uart = IO(Vec(1, new UARTPortIO()))

        val txFromVerilog = Wire(Bool())
        val rxFromVerilog = Wire(Bool())

        txFromVerilog := DontCare
        uart(0).txd := txFromVerilog
        rxFromVerilog := uart(0).rxd
}

object UARTWrapperDriver extends App {
        chisel3.Driver.execute(args, () => new UARTWrapper)
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

  def addTestSuites {
    // val names = ParsedInputNames("/home/generated-src/f1/PicoRV32-PicoRV32Config-FireSimConfig", "firesim.picorv32", "PicoRV32", "firesim.firesim", "PicoRV32Config")
    // val params = getParameters(names.fullConfigClasses)
    // val coreParams =
    //   if (params(RocketTilesKey).nonEmpty) {
    //     params(RocketTilesKey).head.core
    //   } else {
    //     params(BoomTilesKey).head.core
    //   }
    val coreParams = RocketCoreParams(useVM = false, fpu = None, mulDiv = Some(MulDivParams(mulUnroll = 8)))
    val xlen = 32 //params(XLen)
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
    val (rvi, rvu) = (rv32pi, rv32u)
      // if (params(BoomTilesKey).nonEmpty) ((if (vm) BoomTestSuites.rv64i else BoomTestSuites.rv64pi), rv64u)
      // else if (xlen == 64) ((if (vm) rv64i else rv64pi), rv64u)
      // else            ((if (vm) rv32i else rv32pi), rv32u)

    TestGeneration.addSuites(rvi.map(_("p")))
    TestGeneration.addSuites((if (vm) List("v") else List()).flatMap(env => rvu.map(_(env))))
    TestGeneration.addSuite(benchmarks)
    TestGeneration.addSuite(new RegressionTestSuite(if (xlen == 64) rv64RegrTestNames else rv32RegrTestNames))
  }
}

