// See LICENSE for license details.
package midas.tests

import chisel3._
import chisel3.util._
import firesim.lib.nasti._
import midas.widgets.MCRFile
import java.nio.file.{Files, Paths}
import java.io.PrintWriter
import org.scalatest.flatspec.AnyFlatSpec
import scala.sys.process._

class MCRReadHarness extends Module {
  private val params = NastiParameters(32, 12, 3)
  val io             = IO(new Bundle {
    val nasti    = Flipped(new NastiIO(params))
    val changing = Input(UInt(32.W))
    val source   = Flipped(Decoupled(UInt(32.W)))
  })
  private val file   = Module(new MCRFile(params, 2))
  file.io.nasti                     <> io.nasti
  file.io.mcr.read(0).bits          := io.changing
  file.io.mcr.read(0).valid         := true.B
  file.io.mcr.read(1)               <> io.source
  file.io.mcr.write.foreach(_.ready := true.B)
}

class MCRFileSpec extends AnyFlatSpec {
  behavior.of("MCRFile read responses")
  private lazy val output = {
    val parent = Files.createDirectories(Paths.get("test_run_dir", suiteName))
    Files.createTempDirectory(parent, "read-").toAbsolutePath
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
      .emitVerilog(new MCRReadHarness, Array("--target-dir", output.toString))
    println(s"MCRFile regression output: $output")
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
          "MCRReadHarness",
          "-Mdir",
          obj.toString,
          output.resolve("MCRReadHarness.v").toString,
          Paths.get("midas/src/test/cc/mcr_file_read_test.cc").toAbsolutePath.toString,
        ),
        "build",
      ) == 0
    )
    obj.resolve("VMCRReadHarness").toString
  }
  for (scenario <- Seq("changing-register", "decoupled-source", "reset-pending")) {
    it should s"hold and retire a single response for $scenario" in {
      assert(run(Seq(binary, scenario), scenario) == 0, s"MCRFile simulation failed; see $output")
    }
  }
}
