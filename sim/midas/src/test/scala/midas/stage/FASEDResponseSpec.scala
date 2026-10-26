// See LICENSE for license details.

package midas.stage

import java.nio.file.{Files, Paths}
import java.io.PrintWriter

import chisel3._
import firrtl.options.TargetDirAnnotation
import firrtl.stage.FirrtlCircuitAnnotation
import firesim.lib.bridges.{CompleteConfig, FASEDBridge}
import firesim.lib.nasti.NastiParameters
import midas.OutputDir
import midas.models.{BaseParams, LatencyPipeConfig}
import midas.stage.phases.ConfigParametersAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import scala.sys.process._

/** Issue four legal single-beat writes, then four reads, in the enabled directions. */
class FASEDResponseTarget extends Module {
  val io     = IO(new Bundle {
    val writes     = Input(Bool())
    val reads      = Input(Bool())
    val writeCount = Output(UInt(3.W))
    val readCount  = Output(UInt(3.W))
  })
  val bridge = Module(new FASEDBridge(CompleteConfig(NastiParameters(64, 16, 2))))
  bridge.io.clock := clock
  bridge.io.reset := reset.asBool
  val bus     = bridge.io.axi4
  val writes  = RegInit(0.U(3.W))
  val reads   = RegInit(0.U(3.W))
  val awSent  = RegInit(false.B)
  val wSent   = RegInit(false.B)
  val arSent  = RegInit(false.B)
  val writing = !reset.asBool && io.writes && writes < 4.U
  val reading = !reset.asBool && (!io.writes || writes === 4.U) && io.reads && reads < 4.U
  bus.aw.bits      := 0.U.asTypeOf(bus.aw.bits)
  bus.aw.bits.addr := writes << 3
  bus.aw.bits.size := 3.U
  bus.aw.valid     := writing && !awSent
  bus.w.bits       := 0.U.asTypeOf(bus.w.bits)
  bus.w.bits.strb  := 255.U
  bus.w.bits.last  := true.B
  bus.w.valid      := writing && !wSent
  bus.b.ready      := true.B
  when(bus.aw.fire) { awSent := true.B }
  when(bus.w.fire) { wSent := true.B }
  when(bus.b.fire) {
    writes := writes + 1.U
    awSent := false.B
    wSent  := false.B
  }
  bus.ar.bits      := 0.U.asTypeOf(bus.ar.bits)
  bus.ar.bits.addr := reads << 3
  bus.ar.bits.size := 3.U
  bus.ar.valid     := reading && !arSent
  bus.r.ready      := true.B
  when(bus.ar.fire) { arSent := true.B }
  when(bus.r.fire) {
    reads  := reads + 1.U
    arSent := false.B
  }
  io.writeCount    := writes
  io.readCount     := reads
}

class FASEDResponseSpec extends AnyFlatSpec {
  protected def hostTimingHold = false
  behavior.of("FASED host response error registers")
  private lazy val output = {
    val parent = Files.createDirectories(Paths.get("test_run_dir", suiteName))
    Files.createTempDirectory(parent, "responses-").toAbsolutePath
  }
  private def run(command: Seq[String], name: String): Int = {
    Files.writeString(output.resolve(s"$name-command.txt"), command.mkString("\n") + "\n")
    val stdout = new PrintWriter(output.resolve(s"$name.stdout").toFile)
    val stderr = new PrintWriter(output.resolve(s"$name.stderr").toFile)
    try Process(command, output.toFile).!(ProcessLogger(stdout.println(_), stderr.println(_)))
    finally { stdout.close(); stderr.close() }
  }
  private lazy val binary = {
    val parameters             = (new firesim.configs.DefaultF1Config).alterPartial {
      case firesim.configs.MemModelKey            =>
        new LatencyPipeConfig(
          BaseParams(
            maxReads               = 2,
            maxWrites              = 2,
            maxReadLength          = 1,
            maxWriteLength         = 1,
            maxReadsPerID          = Some(1),
            maxWritesPerID         = Some(1),
            targetAddressOffset    = Some(0),
            targetAddressSpaceSize = Some(BigInt(1) << 16),
            occupancyHistograms    = Nil,
          )
        )
      case midas.HostPayloadHoldEnabled           => hostTimingHold
      case midas.HostTriggerSnapshotEnabled       => hostTimingHold
      case midas.HostTargetOutputIsolationEnabled => hostTimingHold
      case OutputDir                              => output.toFile
    }
    val (circuit, annotations) = midas.ElaborateChiselSubCircuit(
      new firesim.lib.testutils.PeekPokeHarness(() => new FASEDResponseTarget)
    )
    val input                  = annotations ++ Seq(
      FirrtlCircuitAnnotation(circuit),
      ConfigParametersAnnotation(parameters),
      TargetDirAnnotation(output.toString),
      OutputBaseFilenameAnnotation("FASEDResponse"),
    )
    val result                 = new GoldenGateCompilerPhase().transform(AddDerivedAnnotations.transform(input))
    val source                 = output.resolve("FASEDResponse.sv")
    Files.writeString(source, result.collect { case firrtl.EmittedVerilogCircuitAnnotation(c) => c.value }.mkString)
    val header                 = result.collectFirst { case GoldenGateOutputFileAnnotation(body, ".const.h") => body }.get
    Files.writeString(output.resolve("FASEDResponse.const.h"), header)
    def register(name: String): String = ("\\." + name + " = ([0-9]+)").r.findFirstMatchIn(header).get.group(1)
    def port(name:     String): String = ("R\"ESC\\(" + name + "\\)ESC\", peek_poke_t::Port\\{\\.address = ([0-9]+)ULL").r
      .findFirstMatchIn(header)
      .get
      .group(1)
    def model(name:    String): String =
      ("R\"ESC\\(" + name + "\\)ESC\", ([0-9]+)").r.findFirstMatchIn(header).get.group(1)
    val constants = Seq("INIT_DONE", "STEP", "DONE", "PRECISE_PEEKABLE").map(n => s"#define ADDR_$n ${register(n)}") ++
      Seq(
        "RESET"       -> "reset",
        "WRITES"      -> "io_writes",
        "READS"       -> "io_reads",
        "WRITE_COUNT" -> "io_writeCount",
        "READ_COUNT"  -> "io_readCount",
      ).map { case (n, v) =>
        s"#define ADDR_$n ${port(v)}"
      } ++ Seq(
        "writeLatency",
        "readLatency",
        "writeMaxReqs",
        "readMaxReqs",
        "relaxFunctionalModel",
        "rrespError",
        "brespError",
      ).map(n => s"#define ADDR_$n ${model(n)}")
    Files.writeString(output.resolve("response_test_addrs.h"), constants.mkString("\n") + "\n")
    val gate      = output.resolve("AbstractClockGate.v")
    Files.copy(Paths.get("AbstractClockGate.v"), gate)
    val obj       = output.resolve("obj")
    val build     = Seq(
      sys.env.getOrElse("VERILATOR", "verilator"),
      "--cc",
      "--exe",
      "--build",
      "-j",
      "2",
      "--assert",
      "-Wno-fatal",
      "--top-module",
      "FPGATop",
      "-Mdir",
      obj.toString,
      "-CFLAGS",
      s"-I$output",
      source.toString,
      gate.toString,
      Paths.get("midas/src/main/verilog/BUFGCE.v").toAbsolutePath.toString,
      Paths.get("midas/src/test/cc/fased_response_test.cc").toAbsolutePath.toString,
    )
    println(s"FASED response regression output: $output")
    assert(run(build, "build") == 0, s"Verilator build failed; see $output")
    obj.resolve("VFPGATop").toString
  }
  for (scenario <- Seq("write-slverr", "write-decerr", "okay-control", "read-control", "mixed-errors")) {
    it should s"retain the correct channel's accepted error for $scenario" in {
      assert(run(Seq(binary, scenario), scenario) == 0, s"Response diagnostic failed; see $output")
    }
  }
}

/** Run the same behavioral checks with optional host timing treatment enabled. */
class FASEDHostTimingSpec extends FASEDResponseSpec {
  override protected def hostTimingHold = true
}
