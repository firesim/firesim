// See LICENSE for license details.
package midas.tests

import java.nio.file.{Files, Paths}
import java.io.PrintWriter
import chisel3._
import chisel3.util._
import chisel3.stage.ChiselStage
import firesim.lib.nasti._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import midas.core._
import midas.widgets.CtrlNastiKey
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import scala.sys.process._

class DDRManagedStreamHarness(bits: Int, sources: Int = 1, sinks: Int = 1)(implicit p: Parameters) extends LazyModule {
  val engine      = LazyModule(
    new DDRManagedStreamEngine(
      p,
      StreamEngineParameters(
        Seq.tabulate(sources)(i => StreamSourceParameters(s"source$i", i, 128, 64)),
        Seq.tabulate(sinks)(i => StreamSinkParameters(s"sink$i", i, 128, 64)),
      ),
    )
  )
  val memory      = AXI4SlaveNode(
    Seq(
      AXI4SlavePortParameters(
        Seq(
          AXI4SlaveParameters(
            address       = Seq(AddressSet(0, (1L << 32) - 1)),
            supportsRead  = TransferSizes(1, math.min(4096, 256 * bits / 8)),
            supportsWrite = TransferSizes(1, math.min(4096, 256 * bits / 8)),
            interleavedId = Some(0),
          )
        ),
        beatBytes = bits / 8,
      )
    )
  )
  memory := engine.fpgaManagedAXI4NodeOpt.get
  lazy val module = new LazyModuleImp(this) {
    val io = IO(new Bundle {
      val mem      = new AXI4Bundle(memory.in.head._2.bundle)
      val ctrl     = Flipped(new NastiIO(p(CtrlNastiKey)))
      val toHost   = Flipped(Vec(sources, Decoupled(UInt(512.W))))
      val fromHost = Vec(sinks, Decoupled(UInt(512.W)))
    })
    io.mem <> memory.in.head._1
    engine.module.io.ctrl   <> io.ctrl
    engine.streamsToHostCPU <> io.toHost
    io.fromHost             <> engine.streamsFromHostCPU
  }
}

class DDRManagedStreamEngineSpec extends AnyFlatSpec {
  behavior.of("Optional bidirectional DDR-managed stream engine")
  private lazy val output            = Files
    .createTempDirectory(Files.createDirectories(Paths.get("test_run_dir", suiteName)), "verilator-")
    .toAbsolutePath
  private def parameters: Parameters = Parameters.empty.alterPartial {
    case CtrlNastiKey                    => NastiParameters(32, 12, 1)
    case DDRManagedStreamReadBufferDepth => 16
  }
  private def run(command: Seq[String], dir: java.nio.file.Path, name: String): Int = {
    Files.writeString(dir.resolve(name + "-command.txt"), command.mkString("\n"))
    val out = new PrintWriter(dir.resolve(name + ".stdout").toFile)
    val err = new PrintWriter(dir.resolve(name + ".stderr").toFile)
    try Process(command, dir.toFile).!(ProcessLogger(out.println(_), err.println(_)))
    finally { out.close(); err.close() }
  }
  for ((sources, sinks) <- Seq((1, 0), (0, 1), (2, 2))) {
    it should s"elaborate $sources source and $sinks sink streams without an unused master" in {
      implicit val p: Parameters = parameters
      val dir                    = Files.createDirectories(output.resolve(s"ports-$sources-$sinks"))
      new ChiselStage().emitVerilog(
        LazyModule(new DDRManagedStreamHarness(64, sources, sinks)).module,
        Array("--target-dir", dir.toString),
      )
    }
  }
  private val binaries               = scala.collection.mutable.Map.empty[Int, (String, java.nio.file.Path)]
  private def binary(bits: Int): (String, java.nio.file.Path) = binaries.getOrElseUpdate(
    bits, {
      implicit val p: Parameters = parameters
      val dir                    = Files.createDirectories(output.resolve(s"width-$bits"))
      val harness                = LazyModule(new DDRManagedStreamHarness(bits))
      new ChiselStage().emitVerilog(harness.module, Array("--target-dir", dir.toString))
      val fields                 = Seq(
        "source0_toHostPhysAddrHigh",
        "source0_toHostPhysAddrLow",
        "source0_bytesAvailable",
        "source0_bytesConsumed",
        "source0_toHostStreamDoneInit",
        "source0_toHostStreamFlush",
        "source0_toHostStreamFlushDone",
        "sink0_fromHostPhysAddrHigh",
        "sink0_fromHostPhysAddrLow",
        "sink0_bytesAvailable",
        "sink0_bytesConsumed",
        "sink0_fromHostStreamDoneInit",
        "sink0_fromHostStreamFlush",
        "sink0_fromHostStreamFlushDone",
      )
      Files.writeString(
        dir.resolve("registers.h"),
        fields.map(n => s"#define $n ${harness.engine.getCRAddr(n)}\n").mkString,
      )
      val obj                    = dir.resolve("obj")
      val command                = Seq(
        "verilator",
        "--cc",
        "--exe",
        "--build",
        "-j",
        "2",
        "--assert",
        "-Wno-fatal",
        "--top-module",
        "DDRManagedStreamHarness",
        "-CFLAGS",
        s"-DAXI_BITS=$bits -I$dir",
        "-Mdir",
        obj.toString,
        dir.resolve("DDRManagedStreamHarness.v").toString,
        Paths.get("midas/src/test/cc/ddr_managed_stream_test.cc").toAbsolutePath.toString,
      )
      println(s"DDR-managed stream regression output: $dir")
      assert(run(command, dir, "build") == 0, s"Build failed: $dir")
      (obj.resolve("VDDRManagedStreamHarness").toString, dir)
    },
  )
  for {
    bits <- Seq(64, 512)
    scenario <- Seq(
                  "write-one",
                  "write-tail",
                  "write-wrap",
                  "write-stalls",
                  "read-one",
                  "read-wrap",
                  "read-reservation",
                  "read-stalls",
                  "duplex",
                  "write-error",
                  "read-error",
                  "read-last-error",
                )
  } {
    it should s"preserve tokens and credits for $scenario at $bits bits" in {
      val (exe, dir) = binary(bits)
      val code       = run(Seq(exe, scenario), dir, scenario)
      if (scenario.endsWith("error")) {
        assert(code != 0, s"Injected AXI error was accepted: $dir/$scenario")
        val log =
          Files.readString(dir.resolve(scenario + ".stdout")) + Files.readString(dir.resolve(scenario + ".stderr"))
        assert(log.contains("DDR stream"), s"Failure did not identify the injected AXI response: $dir/$scenario")
      } else assert(code == 0, s"Simulation failed: $dir/$scenario")
    }
  }
}
