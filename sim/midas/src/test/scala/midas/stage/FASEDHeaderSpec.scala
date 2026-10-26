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

/** Inert AXI master: exercise real bridge elaboration and driver metadata emission. */
class FASEDHeaderTarget extends Module {
  val io = IO(new Bundle { val idle = Output(Bool()) })
  io.idle := true.B
  val bridge = Module(new FASEDBridge(CompleteConfig(NastiParameters(64, 16, 2))))
  bridge.io.clock         := clock
  bridge.io.reset         := reset.asBool
  bridge.io.axi4.ar.valid := false.B
  bridge.io.axi4.ar.bits  := 0.U.asTypeOf(bridge.io.axi4.ar.bits)
  bridge.io.axi4.aw.valid := false.B
  bridge.io.axi4.aw.bits  := 0.U.asTypeOf(bridge.io.axi4.aw.bits)
  bridge.io.axi4.w.valid  := false.B
  bridge.io.axi4.w.bits   := 0.U.asTypeOf(bridge.io.axi4.w.bits)
  bridge.io.axi4.r.ready  := true.B
  bridge.io.axi4.b.ready  := true.B
}

class FASEDHeaderSpec extends AnyFlatSpec {
  behavior.of("FASED generated driver headers")

  val base  = BaseParams(
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
  val cases = Seq(
    ("control", base, Seq.empty[String]),
    ("host-cycle", base.copy(localHCycleCount = true), Seq("hostCycle")),
    (
      "stalls",
      base.copy(stallEventCounters = true),
      Seq(
        "writeStalled",
        "readStalled",
        "tokenStalled",
        "hostMemIdleCycles",
        "hostWritesOutstanding",
        "hostReadsOutstanding",
      ),
    ),
    ("ranges", base.copy(addrRangeCounters = 4), Seq("numRanges", "readRanges_dataL", "writeRanges_dataL")),
    ("collisions", base.copy(detectAddressCollisions = true), Seq("addrCollision", "collisionAddr")),
    (
      "all-profiling",
      base.copy(
        localHCycleCount        = true,
        stallEventCounters      = true,
        addrRangeCounters       = 4,
        latencyHistograms       = true,
        detectAddressCollisions = true,
      ),
      Seq("hostCycle", "numRanges", "hostReadLatencyHist_dataL", "addrCollision"),
    ),
  )

  for ((name, settings, expectedRegisters) <- cases) {
    it should s"compile the $name configuration against the actual C++ bridge drivers" in {
      val parent                 = Files.createDirectories(Paths.get("test_run_dir", suiteName))
      val output                 = Files.createTempDirectory(parent, s"$name-").toAbsolutePath
      val parameters             = (new firesim.configs.DefaultF1Config).alterPartial {
        case firesim.configs.MemModelKey => new LatencyPipeConfig(settings)
        case OutputDir                   => output.toFile
      }
      val (circuit, annotations) = midas.ElaborateChiselSubCircuit(
        new firesim.lib.testutils.PeekPokeHarness(() => new FASEDHeaderTarget)
      )
      val input                  = annotations ++ Seq(
        FirrtlCircuitAnnotation(circuit),
        ConfigParametersAnnotation(parameters),
        TargetDirAnnotation(output.toString),
        OutputBaseFilenameAnnotation("FASEDHeader"),
      )
      val result                 = new GoldenGateCompilerPhase().transform(AddDerivedAnnotations.transform(input))
      val header                 = result.collectFirst { case GoldenGateOutputFileAnnotation(body, ".const.h") =>
        body
      }.get
      Files.writeString(output.resolve("FASEDHeader.const.h"), header)
      val verilog                = result.collect { case firrtl.EmittedVerilogCircuitAnnotation(circuit) => circuit.value }.mkString
      Files.writeString(output.resolve("FASEDHeader.sv"), verilog)
      expectedRegisters.foreach(register => assert(header.contains(s"R\"ESC($register)ESC\"")))
      Files.writeString(
        output.resolve("driver.cc"),
        """|#define GET_INCLUDES
           |#include "FASEDHeader.const.h"
           |#undef GET_INCLUDES
           |#include "core/widget_registry.h"
           |#define GET_SUBSTRUCT_CHECKS
           |#include "FASEDHeader.const.h"
           |#undef GET_SUBSTRUCT_CHECKS
           |void instantiate(widget_registry_t &registry, simif_t &simif,
           |                 const std::vector<std::string> &args) {
           |#define GET_BRIDGE_CONSTRUCTOR
           |#include "FASEDHeader.const.h"
           |}
           |""".stripMargin,
      )
      val command                = Seq(
        "g++",
        "-std=c++20",
        "-fsyntax-only",
        "-I",
        Paths.get("midas/src/main/cc").toAbsolutePath.toString,
        output.resolve("driver.cc").toString,
      )
      Files.writeString(output.resolve("command.txt"), command.mkString("\n") + "\n")
      val stdout                 = new PrintWriter(output.resolve("compile.stdout").toFile)
      val stderr                 = new PrintWriter(output.resolve("compile.stderr").toFile)
      println(s"FASED header regression output: $output")
      val exitCode               =
        try Process(command).!(ProcessLogger(stdout.println(_), stderr.println(_)))
        finally { stdout.close(); stderr.close() }
      assert(exitCode == 0, s"Generated driver header failed C++ compilation; see $output/compile.stderr")
    }
  }
}
