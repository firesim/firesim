// See LICENSE for license details.

package midas.tests

import java.nio.file.{Files, Paths}
import java.io.PrintWriter
import chisel3._
import chisel3.util._
import chisel3.stage.ChiselStage
import firesim.lib.nasti.NastiParameters
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import midas.core._
import midas.widgets.CtrlNastiKey
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import scala.sys.process._

/** Real upstream CPUManagedStreamEngine, including SyncReadMem queues and width adapters. */
class SlashDDRStreamIntegrationHarness(implicit p: Parameters) extends LazyModule {
  val engine = LazyModule(
    new CPUManagedStreamEngine(
      p,
      StreamEngineParameters(
        Seq.tabulate(2)(i => StreamSourceParameters(s"source$i", i, 4, 64)),
        Seq.tabulate(2)(i => StreamSinkParameters(s"sink$i", i, 4, 64)),
      ),
    )
  )
  val master = AXI4MasterNode(Seq(AXI4MasterPortParameters(Seq(AXI4MasterParameters("dma")))))
  engine.cpuManagedAXI4NodeOpt.get := master
  lazy val module = new LazyModuleImp(this) {
    val dma = Module(
      new SlashDDRStreamDMA(AXI4BundleParameters(64, 64, 1), master.out.head._2.bundle, 4096, 1 << 20, 2048)
    )
    val io  = IO(new Bundle {
      val mem           = new AXI4Bundle(AXI4BundleParameters(64, 64, 1))
      val address       = Input(UInt(64.W))
      val streamAddress = Input(UInt(32.W))
      val bytes         = Input(UInt(32.W))
      val command       = Flipped(Decoupled(UInt(32.W)))
      val status        = Output(UInt(32.W))
      val toHost        = Flipped(Vec(2, Decoupled(UInt(512.W))))
      val fromHost      = Vec(2, Decoupled(UInt(512.W)))
    })
    io.mem <> dma.io.mem
    dma.io.address          := io.address
    dma.io.streamAddress    := io.streamAddress
    dma.io.bytes            := io.bytes
    dma.io.command          <> io.command
    io.status               := dma.io.status
    master.out.head._1      <> dma.io.stream
    engine.streamsToHostCPU <> io.toHost
    io.fromHost             <> engine.streamsFromHostCPU
    val ctrl = engine.module.io.ctrl
    ctrl.aw.valid := false.B
    ctrl.aw.bits  := 0.U.asTypeOf(ctrl.aw.bits)
    ctrl.w.valid  := false.B
    ctrl.w.bits   := 0.U.asTypeOf(ctrl.w.bits)
    ctrl.ar.valid := false.B
    ctrl.ar.bits  := 0.U.asTypeOf(ctrl.ar.bits)
    ctrl.b.ready  := true.B
    ctrl.r.ready  := true.B
  }
}

class SlashDDRStreamIntegrationSpec extends AnyFlatSpec {
  behavior.of("SLASH DMA with the upstream CPU-managed stream engine")
  private lazy val output = {
    val parent = Files.createDirectories(Paths.get("test_run_dir", suiteName))
    Files.createTempDirectory(parent, "verilator-").toAbsolutePath
  }
  private def run(command: Seq[String], name: String): Int = {
    val stdout = new PrintWriter(output.resolve(name + ".stdout").toFile)
    val stderr = new PrintWriter(output.resolve(name + ".stderr").toFile)
    try Process(command, output.toFile).!(
      ProcessLogger(
        line => {
          stdout.println(line)
          stdout.flush()
        },
        line => {
          stderr.println(line)
          stderr.flush()
        },
      )
    )
    finally {
      stdout.close()
      stderr.close()
    }
  }
  private lazy val compiled = scala.util.Try {
    implicit val p: Parameters = Parameters.empty.alterPartial {
      case CPUManagedAXI4Key => Some(CPUManagedAXI4Params(32, 64, 1))
      case CtrlNastiKey      => NastiParameters(32, 12, 1)
    }
    new ChiselStage()
      .emitVerilog(LazyModule(new SlashDDRStreamIntegrationHarness).module, Array("--target-dir", output.toString))
    val obj                    = output.resolve("obj")
    val command                = Seq(
      sys.env.getOrElse("VERILATOR", "verilator"),
      "--cc",
      "--exe",
      "--build",
      "-j",
      "2",
      "--assert",
      "-Wno-fatal",
      "--top-module",
      "SlashDDRStreamIntegrationHarness",
      "--prefix",
      "VSlashDDRStreamDMA",
      "-CFLAGS",
      "-DSLASH_REAL_STREAM_ENGINE",
      "-Mdir",
      obj.toString,
      output.resolve("SlashDDRStreamIntegrationHarness.v").toString,
      Paths.get("midas/src/test/cc/slash_ddr_stream_test.cc").toAbsolutePath.toString,
    )
    Files.writeString(output.resolve("build-command.txt"), command.mkString("\n"))
    assert(run(command, "build") == 0, s"Verilator build failed; see $output")
    println(s"SLASH stream integration output: $output")
    obj.resolve("VSlashDDRStreamDMA").toString
  }
  private def binary        = compiled.get
  for {
    channel  <- Seq(0, 1)
    scenario <- Seq("read", "write", "read-stalls", "write-stalls", "repeat", "write-repeat")
  } {
    it should s"transfer complete tokens for $scenario on channel $channel" in {
      assert(
        run(Seq(binary, scenario, "2048", channel.toString), s"$scenario-$channel") == 0,
        s"$scenario failed; see $output",
      )
    }
  }
}
