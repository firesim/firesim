// See LICENSE for license details.

package midas.tests

import java.nio.file.{Files, Paths}
import java.io.PrintWriter

import chisel3._
import chisel3.util._
import chisel3.stage.ChiselStage
import firesim.lib.nasti.NastiParameters
import junctions.NastiKey
import midas.models._
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec
import scala.sys.process._

/** A thin external interface to the real bank-conflict timing model. */
class BankConflictRegressionHarness(maxRequests: Int) extends Module {
  implicit val p = Parameters.empty.alterPartial { case NastiKey =>
    NastiParameters(64, 16, 2)
  }
  val io         = IO(new Bundle {
    val readValid          = Input(Bool())
    val writeValid         = Input(Bool())
    val readId             = Input(UInt(2.W))
    val writeId            = Input(UInt(2.W))
    val responseReady      = Input(Bool())
    val readReady          = Output(Bool())
    val writeReady         = Output(Bool())
    val readResponseValid  = Output(Bool())
    val writeResponseValid = Output(Bool())
    val readResponseId     = Output(UInt(2.W))
    val writeResponseId    = Output(UInt(2.W))
  })
  val model      = Module(
    new BankConflictModel(
      BankConflictConfig(
        4,
        params = BaseParams(
          maxReads            = maxRequests,
          maxWrites           = maxRequests,
          maxReadLength       = 1,
          maxWriteLength      = 1,
          maxReadsPerID       = Some(1),
          maxWritesPerID      = Some(1),
          occupancyHistograms = Nil,
        ),
      )
    )
  )
  model.io.mmReg.readMaxReqs := maxRequests.U
  model.io.mmReg.writeMaxReqs    := maxRequests.U
  model.io.mmReg.latency         := 7.U
  model.io.mmReg.conflictPenalty := 3.U
  model.io.mmReg.bankAddr.offset := 3.U
  model.io.mmReg.bankAddr.mask   := 3.U

  val bus = model.io.tNasti
  bus.ar.bits           := 0.U.asTypeOf(bus.ar.bits)
  bus.aw.bits           := 0.U.asTypeOf(bus.aw.bits)
  bus.w.bits            := 0.U.asTypeOf(bus.w.bits)
  bus.ar.bits.id        := io.readId
  bus.ar.bits.addr      := io.readId << 3
  bus.ar.bits.size      := 3.U
  bus.aw.bits.id        := io.writeId
  bus.aw.bits.addr      := 256.U + (io.writeId << 3)
  bus.aw.bits.size      := 3.U
  bus.w.bits.last       := true.B
  bus.w.bits.strb       := "hff".U
  bus.ar.valid          := io.readValid
  // Submit each single-beat write's address and data together.
  bus.aw.valid          := io.writeValid && bus.w.ready
  bus.w.valid           := io.writeValid && bus.aw.ready
  bus.r.ready           := io.responseReady
  bus.b.ready           := io.responseReady
  io.readReady          := bus.ar.ready
  io.writeReady         := bus.aw.ready && bus.w.ready
  io.readResponseValid  := bus.r.valid
  io.writeResponseValid := bus.b.valid
  io.readResponseId     := bus.r.bits.id
  io.writeResponseId    := bus.b.bits.id

  // The timing model asks the external functional model for response payloads.
  // Supply ID-correct single-beat responses without changing timing-model state.
  val reads = Module(new Queue(UInt(2.W), maxRequests + 1))
  reads.io.enq.valid := model.io.egressReq.r.valid
  reads.io.enq.bits  := model.io.egressReq.r.bits
  reads.io.deq.ready := bus.r.fire
  val writes = Module(new Queue(UInt(2.W), maxRequests + 1))
  writes.io.enq.valid            := model.io.egressReq.b.valid
  writes.io.enq.bits             := model.io.egressReq.b.bits
  writes.io.deq.ready            := bus.b.fire
  assert(!reads.io.enq.valid || reads.io.enq.ready)
  assert(!writes.io.enq.valid || writes.io.enq.ready)
  when(bus.r.valid) { assert(reads.io.deq.valid) }
  when(bus.b.valid) { assert(writes.io.deq.valid) }
  model.io.egressResp.rBits      := 0.U.asTypeOf(model.io.egressResp.rBits)
  model.io.egressResp.rBits.id   := reads.io.deq.bits
  model.io.egressResp.rBits.last := true.B
  model.io.egressResp.bBits      := 0.U.asTypeOf(model.io.egressResp.bBits)
  model.io.egressResp.bBits.id   := writes.io.deq.bits
}

class BankConflictModelSpec extends AnyFlatSpec {
  behavior.of("BankConflictModel")

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

  private def binary(capacity: Int): String = binaries.getOrElseUpdate(
    capacity, {
      val modelOutput = Files.createDirectories(output.resolve(s"requests-$capacity"))
      val tool        = sys.env.getOrElse("VERILATOR", "verilator")
      val rtl         = new ChiselStage()
        .emitVerilog(new BankConflictRegressionHarness(capacity), Array("--target-dir", modelOutput.toString))
      val source      = modelOutput.resolve("BankConflictRegressionHarness.v")
      Files.writeString(source, rtl)
      val test        = Paths.get("midas/src/test/cc/bank_conflict_test.cc").toAbsolutePath
      val obj         = modelOutput.resolve("obj")
      val command     = Seq(
        tool,
        "--cc",
        "--exe",
        "--build",
        "-j",
        "2",
        "--assert",
        "-Wno-fatal",
        "--top-module",
        "BankConflictRegressionHarness",
        "-Mdir",
        obj.toString,
        source.toString,
        test.toString,
      )
      Files.writeString(modelOutput.resolve("build-command.txt"), command.mkString("\n"))
      assert(run(command, s"build-$capacity") == 0, s"Verilator build failed; see $output")
      println(s"Bank-conflict regression output: $output")
      obj.resolve("VBankConflictRegressionHarness").toString
    },
  )

  for {
    capacity <- Seq(1, 4)
    scenario <- Seq("read-only", "write-only", "simultaneous", "response-stalls")
  } {
    it should s"complete every accepted transaction with $scenario traffic and capacity $capacity" in {
      assert(run(Seq(binary(capacity), scenario), s"$scenario-$capacity") == 0, s"$scenario failed; see $output")
    }
  }
}
