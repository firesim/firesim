package firesim.picorv32

import java.io.{File, FileWriter}
import scala.io.Source

import chisel3.experimental.RawModule
import chisel3.internal.firrtl.{Circuit, Port, Width}
import chisel3.core._
import chisel3.experimental.MultiIOModule

import freechips.rocketchip.diplomacy._
import freechips.rocketchip.devices.debug.DebugIO
import freechips.rocketchip.util.{HasGeneratorUtilities, ParsedInputNames, HeterogeneousBag}
import freechips.rocketchip.system.DefaultTestSuites._
import freechips.rocketchip.system.{TestGeneration, RegressionTestSuite}
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.subsystem.RocketTilesKey
import freechips.rocketchip.tile.XLen
import freechips.rocketchip.config.Config
import freechips.rocketchip.rocket.{RocketCoreParams, MulDivParams}
import freechips.rocketchip.amba.axi4.{AXI4BundleParameters,AXI4Bundle}

import boom.system.{BoomTilesKey, BoomTestSuites}

import sifive.blocks.devices.uart.UARTPortIO

import firrtl.annotations.Annotation

import firesim.util.{GeneratorArgs,HasTargetAgnosticUtilites}

import midas.targetutils.FpgaDebug

trait FireSimGeneratorUtils extends HasTestSuites with HasTargetAgnosticUtilites {
  val targetTransforms = Seq(
    firesim.passes.AsyncResetRegPass,
    firesim.passes.PlusArgReaderPass
  )

  lazy val hostTransforms = Seq(
    new firesim.passes.ILATopWiringTransform(genDir)
  )

  // We're dealing with a FIRRTL source rather than Chisel, so there are no configs to worry about. All of this is hardcoded.
  val targetDir = "/home/centos/firesim/sim/generated-src/f1/PicoRV32-EmptyConfig-PicoRV32Config/"
  lazy val genDir = new File(targetDir)

  def hostParams = (new Config(new firesim.firesim.PicoRV32Config)).toInstance

  def elaborateAndCompileWithMidas {
    val lines = Source.fromFile("src/main/scala/picorv32/synth.fir").getLines()
    val chirrtl = firrtl.Parser.parse(lines)

    val dut = chisel3.Driver.elaborate(() => new PicoRV32)
    val annos = dut.annotations.map(_.toFirrtl)
    val portList = dut.components.find(_.name == "PicoRV32").get.ports.flatMap(p => Some(p.id.instanceName -> p.id)) // name here should be the name of the top-level wrapper

    midas.MidasCompiler(
      chirrtl, annos, portList, genDir, None, targetTransforms, hostTransforms
      )(hostParams)
    // Need replay
  }

  def generateTestSuiteMakefrags {
    addTestSuites
    writeOutputFile("PicoRV32.d", TestGeneration.generateMakefrag) // Subsystem-specific test suites
  }
}

object FireSimGenerator extends App with FireSimGeneratorUtils {
  require (args.size == 1, "Command line arg must be output directory!")
  lazy val generatorArgs = GeneratorArgs(args)
  elaborateAndCompileWithMidas
  generateTestSuiteMakefrags
  generateHostVerilogHeader
}

// This is what glues everything together - the top-level wrapper. This contains the UART wrapper that talks to the FireSim/MIDAS UART endpoint and the AXI4 master port that talks to DRAM. You don't need to worry about this for this design, as everything's been wired together in synth.fir, but for your own design you'll have to emit FIRRTL for your wrapper (using something like WrapperDriver), then tie all the lines together by hand. See the PicoRV32 module in synth.fir for an example of how this is done. Some lines here are hardcoded to fixed values, as the PicoRV32 has an AXI4Lite interface rather than full AXI4.

class PicoRV32_BB extends BlackBox {
	val io = IO(new Bundle {
		// clock and reset
		val clk = Input(UInt(1.W))
		val resetn = Input(UInt(1.W))

		// AXI4 interface
		val mem_axi_awvalid = Output(UInt(1.W))
		val mem_axi_awready = Input(UInt(1.W))
		val mem_axi_awaddr = Output(UInt(32.W))
		val mem_axi_awprot = Output(UInt(3.W))
		val mem_axi_wvalid = Output(UInt(1.W))
		val mem_axi_wready = Input(UInt(1.W))
		val mem_axi_wdata = Output(UInt(32.W))
		val mem_axi_wstrb = Output(UInt(4.W))
		val mem_axi_bvalid = Input(UInt(1.W))
		val mem_axi_bready = Output(UInt(1.W))
		val mem_axi_arvalid = Output(UInt(1.W))
		val mem_axi_arready = Input(UInt(1.W))
		val mem_axi_araddr = Output(UInt(32.W))
		val mem_axi_arprot = Output(UInt(3.W))
		val mem_axi_rvalid = Input(UInt(1.W))
		val mem_axi_rready = Output(UInt(1.W))
		val mem_axi_rdata = Input(UInt(32.W))

		// UART interface
		val ser_tx = Output(UInt(1.W))
		val ser_rx = Input(UInt(1.W))
	})
}	

class PicoRV32 extends MultiIOModule {
        val uart = IO(Vec(1, new UARTPortIO()))
	val mem_axi4 = IO(HeterogeneousBag(Seq(AXI4Bundle(new AXI4BundleParameters(addrBits=32, dataBits=32, idBits=1, userBits=0, wcorrupt=false)))))

	val soc = Module(new PicoRV32_BB)

	// explicit clock and reset
	soc.io.clk := clock.asUInt
	soc.io.resetn := ~(reset.asUInt)

	// AXI4 interface
	soc.io.mem_axi_awready := mem_axi4(0).aw.ready
	soc.io.mem_axi_arready := mem_axi4(0).ar.ready
	soc.io.mem_axi_bvalid := mem_axi4(0).b.valid
	soc.io.mem_axi_rdata := mem_axi4(0).r.bits.data
	soc.io.mem_axi_rvalid := mem_axi4(0).r.valid
	soc.io.mem_axi_wready := mem_axi4(0).w.ready
	mem_axi4(0).aw.bits.id := 0.U
	mem_axi4(0).aw.bits.addr := soc.io.mem_axi_awaddr
	mem_axi4(0).aw.bits.len := 0.U
	mem_axi4(0).aw.bits.size := 2.U
	mem_axi4(0).aw.bits.burst := 1.U
	mem_axi4(0).aw.bits.lock := 0.U
	mem_axi4(0).aw.bits.cache := 0.U
	mem_axi4(0).aw.bits.prot := soc.io.mem_axi_awprot
	mem_axi4(0).aw.bits.qos := 0.U
	mem_axi4(0).aw.valid := soc.io.mem_axi_awvalid
	mem_axi4(0).ar.bits.id := 0.U
	mem_axi4(0).ar.bits.addr := soc.io.mem_axi_araddr
	mem_axi4(0).ar.bits.len := 0.U
	mem_axi4(0).ar.bits.size := 2.U
	mem_axi4(0).ar.bits.burst := 1.U
	mem_axi4(0).ar.bits.lock := 0.U
	mem_axi4(0).ar.bits.cache := 0.U
	mem_axi4(0).ar.bits.prot := soc.io.mem_axi_arprot
	mem_axi4(0).ar.bits.qos := 0.U
	mem_axi4(0).ar.valid := soc.io.mem_axi_arvalid
	mem_axi4(0).w.bits.data := soc.io.mem_axi_wdata
	mem_axi4(0).w.bits.strb := soc.io.mem_axi_wstrb
	mem_axi4(0).w.bits.last := 1.U
	mem_axi4(0).w.valid := soc.io.mem_axi_wvalid
	mem_axi4(0).b.ready := soc.io.mem_axi_bready
	mem_axi4(0).r.ready := soc.io.mem_axi_rready

	// UART interface
	uart(0).txd := soc.io.ser_tx
	soc.io.ser_rx := uart(0).rxd
}

object WrapperDriver extends App {
        chisel3.Driver.execute(args, () => new PicoRV32)
}

// Most of these aren't necessary, but are present to avoid completely rewriting the Rocket generator for FireSim. Don't worry too much about anything that follows.
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

