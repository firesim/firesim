// See LICENSE for license details.
package midas.tests

import chisel3._
import firesim.lib.nasti._
import freechips.rocketchip.diplomacy._
import midas.widgets._
import midas.targetutils.PerfCounterOps
import org.chipsalliance.cde.config.Parameters
import java.nio.file.{Files, Path, Paths}
import java.io.PrintWriter
import org.scalatest.flatspec.AnyFlatSpec
import scala.sys.process._

class AutoCounterBackpressureHarness(output: Path)(implicit p: Parameters) extends LazyModule {
  private val events = Seq(
    EventMetadata("accum", "accum", "Accumulated events", 8, PerfCounterOps.Accumulate),
    EventMetadata("identity", "identity", "Current token", 16, PerfCounterOps.Identity),
  )
  val bridge         = LazyModule(new AutoCounterBridgeModule(AutoCounterParameters(events, "trigger", "underReset")))
  lazy val module    = new LazyModuleImp(this) {
    val io   = IO(new Bundle {
      val ctrl        = Flipped(new NastiIO(p(CtrlNastiKey)))
      val valid       = Input(Bool())
      val returnReady = Input(Bool())
      val ready       = Output(Bool())
      val trigger     = Input(Bool())
      val underReset  = Input(Bool())
      val accum       = Input(UInt(8.W))
      val identity    = Input(UInt(16.W))
    })
    bridge.module.io.ctrl <> io.ctrl
    val port = bridge.module.hPort
    port.toHost.hValid          := io.valid
    port.fromHost.hReady        := io.returnReady
    io.ready                    := port.toHost.hReady
    port.hBits.triggerEnable    := io.trigger
    port.hBits.underGlobalReset := io.underReset
    port.hBits.events(0)._2     := io.accum
    port.hBits.events(1)._2     := io.identity
    val names = Seq("readrate_low", "readrate_high", "init_done", "countersready", "readdone") ++
      Seq("local_cycle", "accum", "identity").flatMap(label =>
        Seq(s"autocounter_low_$label", s"autocounter_high_$label")
      )
    Files.writeString(
      output.resolve("autocounter_regs.h"),
      names.map(n => s"#define REG_$n ${bridge.getCRAddr(n)}u\n").mkString,
    )
  }
}

class AutoCounterBackpressureSpec extends AnyFlatSpec {
  behavior.of("AutoCounter target token backpressure")
  private lazy val output = {
    val parent = Files.createDirectories(Paths.get("test_run_dir", suiteName))
    Files.createTempDirectory(parent, "counter-").toAbsolutePath
  }
  private def run(command: Seq[String], name: String): Int = {
    Files.writeString(output.resolve(name + "-command.txt"), command.mkString("\n"))
    val out = new PrintWriter(output.resolve(name + ".stdout").toFile)
    val err = new PrintWriter(output.resolve(name + ".stderr").toFile)
    try Process(command, output.toFile).!(ProcessLogger(out.println(_), err.println(_)))
    finally { out.close(); err.close() }
  }
  private lazy val binary = {
    implicit val p: Parameters = Parameters.empty.alterPartial { case CtrlNastiKey => NastiParameters(32, 12, 1) }
    new chisel3.stage.ChiselStage().emitVerilog(
      LazyModule(new AutoCounterBackpressureHarness(output)).module,
      Array("--target-dir", output.toString),
    )
    println(s"AutoCounter backpressure regression output: $output")
    val obj                    = output.resolve("obj")
    assert(
      run(
        Seq(
          "verilator",
          "--cc",
          "--exe",
          "--build",
          "-j",
          "2",
          "--assert",
          "-Wno-fatal",
          "--top-module",
          "AutoCounterBackpressureHarness",
          "-Mdir",
          obj.toString,
          "-CFLAGS",
          s"-I$output",
          output.resolve("AutoCounterBackpressureHarness.v").toString,
          Paths.get("midas/src/test/cc/autocounter_backpressure_test.cc").toAbsolutePath.toString,
        ),
        "build",
      ) == 0
    )
    obj.resolve("VAutoCounterBackpressureHarness").toString
  }
  for (scenario <- Seq("fast-drain", "slow-drain", "trigger-gap", "reset-pending")) {
    it should s"preserve every enabled sample for $scenario" in {
      assert(run(Seq(binary, scenario), scenario) == 0, s"AutoCounter simulation failed; see $output")
    }
  }
}
