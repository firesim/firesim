// See LICENSE for license details.
package midas.stage

import java.nio.file.{Files, Paths}
import java.io.PrintWriter
import chisel3._
import firrtl.options.TargetDirAnnotation
import firrtl.stage.FirrtlCircuitAnnotation
import firesim.lib.bridgeutils._
import midas.OutputDir
import midas.stage.phases.ConfigParametersAnnotation
import midas.models.{BaseParams, LatencyPipeConfig}
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import scala.sys.process._

class SlashFPGATestBridge extends BlackBox with Bridge[SlashTestStreamHostIO] {
  val moduleName     = "midas.stage.SlashFPGATestBridgeModule"
  val io             = IO(new SlashTestStreamTargetIO)
  val bridgeIO       = new SlashTestStreamHostIO(io)
  val constructorArg = None
  generateAnnotations()
}
class SlashFPGATestBridgeModule(implicit p: Parameters) extends SlashTestStreamBridgeModule {
  override val toHostCPUQueueDepth   = 128
  override val fromHostCPUQueueDepth = 128
}
class SlashFPGATestTarget extends FASEDHeaderTarget                           {
  val stream = Module(new SlashFPGATestBridge)
  stream.io.clock := clock
  stream.io.value := reset.asBool
}

class SlashFPGAManagedPlatformSpec extends AnyFlatSpec {
  behavior.of("Complete SLASH FPGA-managed DDR stream path")
  it should "roundtrip generated native stream parameters through the real shared DDR platform" in {
    val output           = Files
      .createTempDirectory(Files.createDirectories(Paths.get("test_run_dir", suiteName)), "platform-")
      .toAbsolutePath
    val p                = (new firesim.configs.DefaultV80SlashFPGAManagedConfig).alterPartial {
      case firesim.configs.MemModelKey                =>
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
      case midas.core.DDRManagedStreamReadBufferDepth => 16
      case OutputDir                                  => output.toFile
    }
    val (circuit, annos) =
      midas.ElaborateChiselSubCircuit(new firesim.lib.testutils.PeekPokeHarness(() => new SlashFPGATestTarget))
    val input            = annos ++ Seq(
      FirrtlCircuitAnnotation(circuit),
      ConfigParametersAnnotation(p),
      TargetDirAnnotation(output.toString),
      OutputBaseFilenameAnnotation("SlashPlatform"),
    )
    val result           = new GoldenGateCompilerPhase().transform(AddDerivedAnnotations.transform(input))
    val rtl              = result.collect { case firrtl.EmittedVerilogCircuitAnnotation(c) => c.value }.mkString
    val header           = result.collectFirst { case GoldenGateOutputFileAnnotation(body, ".const.h") => body }.get
    Files.writeString(output.resolve("SlashPlatform.sv"), rtl)
    Files.writeString(output.resolve("SlashPlatform.const.h"), header)
    assert(rtl.contains("DDRManagedStreamEngine"))
    assert(!header.contains("V80_DDR_STREAM_METADATA"))
    assert(header.contains("FPGAManagedStreams::Target::HostMemory"))
    assert(header.contains(".fpga_managed_ddr = true"))
    assert(header.contains(".target_dram_bytes = 65536ULL"))
    val source           = "(?s)FPGAManagedStreams::StreamParameters\\(.*?\\n\\)".r.findFirstIn(header).get
    val sink             = "(?s)FPGAManagedStreams::FromCPUStreamParameters\\(.*?\\n\\)".r.findFirstIn(header).get
    Files.writeString(
      output.resolve("driver-parameters.h"),
      s"inline auto source_parameters() { return $source; }\ninline auto sink_parameters() { return $sink; }\n",
    )
    val obj              = output.resolve("obj")
    val command          = Seq(
      "verilator",
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
      s"-ffunction-sections -fdata-sections -I$output -I${Paths.get("midas/src/main/cc").toAbsolutePath}",
      "-LDFLAGS",
      "-Wl,--gc-sections",
      output.resolve("SlashPlatform.sv").toString,
      Paths.get("../platforms/xilinx_alveo_v80_slash/cl_firesim/design/V80SlashClockGate.v").toAbsolutePath.toString,
      Paths.get("midas/src/test/rtl/slash_primitives.sv").toAbsolutePath.toString,
      Paths.get("midas/src/main/cc/bridges/fpga_managed_stream.cc").toAbsolutePath.toString,
      Paths.get("midas/src/test/cc/slash_fpga_platform_test.cc").toAbsolutePath.toString,
    )
    def run(cmd: Seq[String], dir: java.nio.file.Path): Int = {
      Files.writeString(dir.resolve("command.txt"), cmd.mkString("\n"))
      val out = new PrintWriter(dir.resolve("stdout.log").toFile)
      val err = new PrintWriter(dir.resolve("stderr.log").toFile)
      try Process(cmd, dir.toFile).!(ProcessLogger(out.println(_), err.println(_)))
      finally { out.close(); err.close() }
    }
    println(s"Full FPGA-managed platform regression output: $output")
    assert(run(command, output) == 0, s"Native build failed: $output")
    for (name <- Seq("roundtrip", "roundtrip-stalls", "ring-wrap")) {
      val dir = Files.createDirectory(output.resolve(name))
      assert(run(Seq(obj.resolve("VSlashPlatform").toString, name), dir) == 0, s"Native regression failed: $dir")
    }
  }
}
