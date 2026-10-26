// See LICENSE for license details.

package midas.tests

import java.nio.file.{Files, Paths}
import java.io.PrintWriter
import freechips.rocketchip.amba.axi4.AXI4BundleParameters
import midas.platform.xilinx.SlashDebugAXIAudit
import org.scalatest.flatspec.AnyFlatSpec
import scala.sys.process._

class SlashDebugAuditSpec extends AnyFlatSpec {
  behavior.of("SLASH passive debug audit")
  private lazy val output = {
    val parent = Files.createDirectories(Paths.get("test_run_dir", suiteName))
    Files.createTempDirectory(parent, "audit-").toAbsolutePath
  }
  private def run(command: Seq[String], name: String): Int = {
    Files.writeString(output.resolve(name + "-command.txt"), command.mkString("\n"))
    val out = new PrintWriter(output.resolve(name + ".stdout").toFile)
    val err = new PrintWriter(output.resolve(name + ".stderr").toFile)
    try Process(command, output.toFile).!(ProcessLogger(out.println(_), err.println(_)))
    finally { out.close(); err.close() }
  }
  private lazy val binary = {
    new chisel3.stage.ChiselStage()
      .emitVerilog(new SlashDebugAXIAudit(AXI4BundleParameters(12, 32, 3)), Array("--target-dir", output.toString))
    println(s"SLASH debug audit regression output: $output")
    val obj = output.resolve("obj")
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
          "SlashDebugAXIAudit",
          "-Mdir",
          obj.toString,
          output.resolve("SlashDebugAXIAudit.v").toString,
          Paths.get("midas/src/test/cc/slash_debug_audit_test.cc").toAbsolutePath.toString,
        ),
        "build",
      ) == 0
    )
    obj.resolve("VSlashDebugAXIAudit").toString
  }
  for (scenario <- Seq("trace", "read-stalls", "write-order")) {
    it should s"preserve trace state and AXI responses for $scenario" in {
      assert(run(Seq(binary, scenario), scenario) == 0, s"Audit simulation failed; see $output")
    }
  }
}
