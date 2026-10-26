// See LICENSE for license details.

package midas.stage

import java.nio.file.{Files, Paths}
import java.io.PrintWriter
import chisel3._
import chisel3.util._
import firrtl.options.TargetDirAnnotation
import firrtl.stage.FirrtlCircuitAnnotation
import firesim.lib.bridgeutils._
import midas.OutputDir
import midas.models.{BaseParams, LatencyPipeConfig}
import midas.stage.phases.ConfigParametersAnnotation
import midas.widgets._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import scala.sys.process._

class SlashTestStreamTargetIO   extends Bundle                                      {
  val clock = Input(Clock())
  val value = Input(Bool())
}
class SlashTestStreamHostIO(private val targetIO: SlashTestStreamTargetIO = new SlashTestStreamTargetIO)
    extends Bundle
    with ChannelizedHostPortIO {
  def targetClockRef = targetIO.clock
  val value          = InputChannel(targetIO.value)
}
class SlashTestStreamBridge     extends BlackBox with Bridge[SlashTestStreamHostIO] {
  val moduleName     = "midas.stage.SlashTestStreamBridgeModule"
  val io             = IO(new SlashTestStreamTargetIO)
  val bridgeIO       = new SlashTestStreamHostIO(io)
  val constructorArg = None
  generateAnnotations()
}
class SlashTestStreamBridgeModule(implicit p: Parameters)
    extends BridgeModule[SlashTestStreamHostIO]
    with StreamToHostCPU
    with StreamFromHostCPU {
  val toHostCPUQueueDepth   = 4
  val fromHostCPUQueueDepth = 4
  lazy val module           = new BridgeModuleImp(this) {
    val io    = IO(new WidgetIO)
    val hPort = IO(new SlashTestStreamHostIO)
    hPort.value.ready := true.B
    val in  = streamDeq.asInstanceOf[DecoupledIO[UInt]]
    val out = streamEnq.asInstanceOf[DecoupledIO[UInt]]
    out <> in
    val tokens = RegInit(0.U(32.W))
    when(out.fire) { tokens := tokens + 1.U }
    attach(tokens, "tokens", ReadOnly, substruct = false)
    genCRFile()
    def genHeader(base: BigInt, regions: Map[String, BigInt], sb: StringBuilder): Unit = {}
  }
}
class SlashPlatformStreamTarget extends FASEDHeaderTarget                           {
  val stream = Module(new SlashTestStreamBridge)
  stream.io.clock := clock
  stream.io.value := reset.asBool
}

class SlashPlatformSpec extends AnyFlatSpec {
  protected def axisDebug = false
  behavior.of("Independent SLASH platform integration")
  for (streams <- Seq(false, true)) {
    it should s"generate the complete platform and metadata with streams=$streams" in {
      val parent                 = Files.createDirectories(Paths.get("test_run_dir", suiteName))
      val output                 = Files.createTempDirectory(parent, if (streams) "streams-" else "memory-").toAbsolutePath
      val p                      = (new firesim.configs.DefaultV80SlashConfig).alterPartial {
        case firesim.configs.MemModelKey =>
          new LatencyPipeConfig(
            BaseParams(
              maxReads               = 2,
              maxWrites              = 2,
              maxReadLength          = 1,
              maxWriteLength         = 1,
              maxReadsPerID          = Some(1),
              maxWritesPerID         = Some(1),
              occupancyHistograms    = Nil,
              targetAddressOffset    = Some(0),
              targetAddressSpaceSize = Some(BigInt(1) << 16),
            )
          )
        case midas.EnableAutoILA         => axisDebug
        case OutputDir                   => output.toFile
      }
      val (circuit, annotations) = midas.ElaborateChiselSubCircuit(
        new firesim.lib.testutils.PeekPokeHarness(() =>
          if (streams) new SlashPlatformStreamTarget else new FASEDHeaderTarget
        )
      )
      val input                  = annotations ++ Seq(
        FirrtlCircuitAnnotation(circuit),
        ConfigParametersAnnotation(p),
        TargetDirAnnotation(output.toString),
        OutputBaseFilenameAnnotation("SlashPlatform"),
      )
      val result                 = new GoldenGateCompilerPhase().transform(AddDerivedAnnotations.transform(input))
      val rtl                    = result.collect { case firrtl.EmittedVerilogCircuitAnnotation(c) => c.value }.mkString
      val header                 = result.collectFirst { case GoldenGateOutputFileAnnotation(body, ".const.h") => body }.get
      Files.writeString(output.resolve("SlashPlatform.sv"), rtl)
      Files.writeString(output.resolve("SlashPlatform.const.h"), header)
      result.collect { case GoldenGateOutputFileAnnotation(body, suffix) =>
        Files.writeString(output.resolve("SlashPlatform" + suffix), body)
      }
      if (axisDebug) {
        val ip = Files.readString(output.resolve("SlashPlatform.ila_firesim.ipgen.tcl"))
        assert(ip.contains("create_ip -name axis_ila"))
        assert(rtl.contains("slash_axi_debug_hub"))
        assert(!result.exists(_.isInstanceOf[midas.AXIDebugLinkAnnotation]))
      }

      assert(rtl.contains("module SlashShim"))
      assert(rtl.contains("host_mem_0_awaddr"))
      assert(!rtl.contains("firesim_clocking"))
      assert(rtl.contains("V80SlashClockGate"))
      assert(header.contains("V80_DDR_STREAM_METADATA") == streams)
      if (streams) {
        for (key <- Seq("DDR_LO", "DDR_HI", "STREAM", "BYTES", "COMMAND", "STATUS")) {
          val address = (s"#define V80_DDR_STREAM_$key ([0-9]+)ULL").r
            .findFirstMatchIn(header)
            .get
            .group(1)
            .toLong
          assert(address >= 0x40 && address < 0x1000 && address % 4 == 0)
        }
        assert(header.contains("#define V80_DDR_STREAM_MIN_DDR 65536ULL"))
        Files.writeString(output.resolve("FireSim-generated.const.h"), "#include \"SlashPlatform.const.h\"\n")
        val obj = output.resolve("obj")
        val extraSources = if (axisDebug) {
          val wrapper = output.resolve("SlashPlatform.ila_wrapper_inst.v")
          Files.copy(Paths.get("SlashPlatform.ila_wrapper_inst.v"), wrapper)
          Seq(wrapper.toString, Paths.get("midas/src/test/rtl/slash_debug_hub.sv").toAbsolutePath.toString)
        } else Seq.empty
        val command      = Seq(
          sys.env.getOrElse("VERILATOR", "verilator"),
          "--cc",
          "--exe",
          "--build",
          "-j",
          "2",
          "--assert",
          "-Wno-fatal",
          "--top-module",
          "SlashShim",
          "--prefix",
          "VSlashPlatform",
          "-Mdir",
          obj.toString,
          "-CFLAGS",
          s"-I$output -I${Paths.get("midas/src/main/cc").toAbsolutePath}" + (if (axisDebug) " -DSLASH_TEST_AXIS_DEBUG"
                                                                             else ""),
          output.resolve("SlashPlatform.sv").toString,
          Paths
            .get("../platforms/xilinx_alveo_v80_slash/cl_firesim/design/V80SlashClockGate.v")
            .toAbsolutePath
            .toString,
          Paths.get("midas/src/test/rtl/slash_primitives.sv").toAbsolutePath.toString,
          Paths.get("midas/src/test/cc/slash_platform_test.cc").toAbsolutePath.toString,
        ) ++ extraSources
        def run(cmd: Seq[String], work: java.nio.file.Path): Int = {
          Files.writeString(work.resolve("command.txt"), cmd.mkString("\n"))
          val out = new PrintWriter(work.resolve("stdout.log").toFile)
          val err = new PrintWriter(work.resolve("stderr.log").toFile)
          try Process(cmd, work.toFile).!(ProcessLogger(out.println(_), err.println(_)))
          finally { out.close(); err.close() }
        }
        assert(run(command, output) == 0, s"Full platform Verilator build failed; see $output")
        for (
          scenario <- Seq("roundtrip", "roundtrip-stalls", "invalid") ++
                        (if (axisDebug) Seq("debug-routing", "debug-routing-stalls") else Seq.empty)
        ) {
          val work = Files.createDirectory(output.resolve(scenario))
          assert(run(Seq(obj.resolve("VSlashPlatform").toString, scenario), work) == 0, s"$scenario failed; see $work")
        }
      }
      println(s"SLASH full platform output: $output")
    }
  }
}

class SlashDebugPlatformSpec extends SlashPlatformSpec {
  override protected def axisDebug = true
}
