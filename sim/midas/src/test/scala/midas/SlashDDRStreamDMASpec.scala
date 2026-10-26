// See LICENSE for license details.

package midas.tests

import java.nio.file.{Files, Paths}
import java.io.PrintWriter
import chisel3.stage.ChiselStage
import freechips.rocketchip.amba.axi4.AXI4BundleParameters
import midas.core.SlashDDRStreamDMA
import org.scalatest.flatspec.AnyFlatSpec
import scala.sys.process._

class SlashDDRStreamDMASpec extends AnyFlatSpec {
  behavior.of("SLASH DDR stream DMA")
  private lazy val output = {
    val parent = Files.createDirectories(Paths.get("test_run_dir", suiteName))
    Files.createTempDirectory(parent, "verilator-").toAbsolutePath
  }
  private def run(command: Seq[String], name: String): Int = {
    val stdout = new PrintWriter(output.resolve(name + ".stdout").toFile)
    val stderr = new PrintWriter(output.resolve(name + ".stderr").toFile)
    try Process(command, output.toFile).!(
      ProcessLogger(
        line => { stdout.println(line); stdout.flush() },
        line => { stderr.println(line); stderr.flush() },
      )
    )
    finally { stdout.close(); stderr.close() }
  }
  private val binaries = scala.collection.mutable.Map.empty[Int, String]
  private def binary(burstBytes: Int): String = binaries.getOrElseUpdate(
    burstBytes, {
      val dir     = Files.createDirectories(output.resolve(s"burst-$burstBytes"))
      new ChiselStage().emitVerilog(
        new SlashDDRStreamDMA(
          AXI4BundleParameters(64, 64, 1),
          AXI4BundleParameters(32, 64, 1),
          4096,
          1 << 20,
          burstBytes,
        ),
        Array("--target-dir", dir.toString),
      )
      val obj     = dir.resolve("obj")
      val command = Seq(
        sys.env.getOrElse("VERILATOR", "verilator"),
        "--cc",
        "--exe",
        "--build",
        "-j",
        "2",
        "--assert",
        "-Wno-fatal",
        "--top-module",
        "SlashDDRStreamDMA",
        "-Mdir",
        obj.toString,
        dir.resolve("SlashDDRStreamDMA.v").toString,
        Paths.get("midas/src/test/cc/slash_ddr_stream_test.cc").toAbsolutePath.toString,
      )
      Files.writeString(dir.resolve("build-command.txt"), command.mkString("\n"))
      assert(run(command, s"build-$burstBytes") == 0, s"Verilator build failed; see $output")
      println(s"SLASH DMA regression output: $output")
      obj.resolve("VSlashDDRStreamDMA").toString
    },
  )
  for {
    burst <- Seq(64, 2048)
    scenario <- Seq(
                  "read",
                  "write",
                  "read-stalls",
                  "write-stalls",
                  "read-error",
                  "write-error",
                  "read-last-error",
                  "invalid-command",
                  "invalid-reserved",
                  "invalid-end",
                  "invalid-wrap",
                  "invalid-token",
                  "invalid-stream",
                  "invalid-zero",
                  "invalid-alignment",
                  "invalid-capacity",
                  "repeat",
                )
  } {
    it should s"handle $scenario with $burst-byte maximum bursts" in {
      assert(
        run(Seq(binary(burst), scenario, burst.toString), s"$scenario-$burst") == 0,
        s"$scenario failed; see $output",
      )
    }
  }
}
