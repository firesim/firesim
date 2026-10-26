// See LICENSE for license details.
package midas.tests

import chisel3._
import firesim.lib.nasti._
import midas.widgets.MCRFile
import java.nio.file.{Files, Paths}
import java.io.PrintWriter
import org.scalatest.flatspec.AnyFlatSpec
import scala.sys.process._

class MCRPaddingHarness extends Module {
  private val params = NastiParameters(32, 12, 3)
  val io             = IO(new Bundle {
    val nasti  = Flipped(new NastiIO(params))
    val reads  = Output(UInt(32.W))
    val writes = Output(UInt(32.W))
  })
  private val file   = Module(new MCRFile(params, 3))
  file.io.nasti <> io.nasti
  for (index <- 0 until 3) {
    file.io.mcr.read(index).valid  := true.B
    file.io.mcr.read(index).bits   := (0x12340000L + index).U
    file.io.mcr.write(index).ready := true.B
  }
  private val reads = RegInit(0.U(32.W))
  private val writes = RegInit(0.U(32.W))
  when(file.io.mcr.read.map(_.fire).reduce(_ || _)) { reads := reads + 1.U }
  when(file.io.mcr.write.map(_.fire).reduce(_ || _)) { writes := writes + 1.U }
  io.reads := reads
  io.writes := writes
}

class MCRPaddingSpec extends AnyFlatSpec {
  behavior.of("MCRFile padded address slots")
  private lazy val output = {
    val parent = Files.createDirectories(Paths.get("test_run_dir", suiteName))
    Files.createTempDirectory(parent, "padding-").toAbsolutePath
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
      .emitVerilog(new MCRPaddingHarness, Array("--target-dir", output.toString))
    println(s"MCR padding regression output: $output")
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
          "MCRPaddingHarness",
          "-Mdir",
          obj.toString,
          output.resolve("MCRPaddingHarness.v").toString,
          Paths.get("midas/src/test/cc/mcr_padding_test.cc").toAbsolutePath.toString,
        ),
        "build",
      ) == 0
    )
    obj.resolve("VMCRPaddingHarness").toString
  }
  for (scenario <- Seq("valid-read", "valid-write", "padding-read", "padding-write")) {
    it should s"complete $scenario without touching an absent register" in {
      assert(run(Seq(binary, scenario), scenario) == 0, s"MCR padding simulation failed; see $output")
    }
  }
}
