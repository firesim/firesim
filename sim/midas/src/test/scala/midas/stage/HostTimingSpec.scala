// See LICENSE for license details.

package midas.stage

import java.nio.file.{Files, Paths}
import java.io.PrintWriter
import chisel3._
import firrtl.options.TargetDirAnnotation
import firrtl.stage.FirrtlCircuitAnnotation
import firesim.lib.bridges.{PeekPokeBridge, RationalClockBridge}
import firesim.lib.bridgeutils.RationalClock
import midas._
import midas.stage.phases.ConfigParametersAnnotation
import midas.targetutils.{TriggerSink, TriggerSource}
import org.scalatest.flatspec.AnyFlatSpec
import scala.sys.process._

/** Cross-clock arithmetic, signed outputs and compiler-generated trigger wiring. */
class HostTimingTarget extends Module {
  val io   = IO(new Bundle {
    val half         = Input(Clock())
    val third        = Input(Clock())
    val seed         = Input(UInt(16.W))
    val enable       = Input(Bool())
    val fast         = Output(UInt(32.W))
    val slow         = Output(UInt(32.W))
    val last         = Output(UInt(32.W))
    val sum          = Output(UInt(32.W))
    val mixed        = Output(UInt(32.W))
    val signed       = Output(SInt(16.W))
    val triggerFast  = Output(UInt(32.W))
    val triggerSlow  = Output(UInt(32.W))
    val triggerThird = Output(UInt(32.W))
  })
  def counter(): UInt = {
    val value = RegInit(0.U(32.W))
    value := value + 1.U
    value
  }
  def triggerCount(): UInt = {
    val enabled = Wire(Bool())
    TriggerSink(enabled, false.B)
    val value   = RegInit(0.U(32.W))
    when(enabled) { value := value + 1.U }
    value
  }
  val fast = counter()
  val sum  = RegInit(0.U(32.W))
  when(io.enable) { sum := sum + io.seed }
  TriggerSource.levelSensitiveEnable(fast(4, 3) === 1.U)
  io.fast := fast
  io.sum         := sum
  io.signed      := (-sum(15, 0).asSInt)
  io.triggerFast := triggerCount()
  val mixedHalf = withClock(io.half) {
    io.slow        := counter()
    io.triggerSlow := triggerCount()
    val value = RegInit(0.U(32.W))
    when(io.enable) { value := value + io.seed + fast(7, 0) }
    value
  }
  withClock(io.third) {
    io.last         := counter()
    io.triggerThird := triggerCount()
    val value = RegInit(0.U(32.W))
    when(io.enable) { value := value + mixedHalf }
    io.mixed := value
  }
}

class HostTimingHarness extends RawModule {
  val clocks = RationalClockBridge(RationalClock("Half", 1, 2), RationalClock("Third", 1, 3)).io.clocks
  val reset  = WireInit(false.B)
  withClockAndReset(clocks.head, reset) {
    val target = Module(new HostTimingTarget)
    target.io.half  := clocks(1)
    target.io.third := clocks(2)
    PeekPokeBridge(
      clocks.head,
      reset,
      target.io.elements.toSeq.filterNot { case (name, _) => Set("half", "third")(name) }: _*
    )
  }
}

class HostTimingSpec extends AnyFlatSpec {
  behavior.of("Optional host timing treatment")
  private lazy val reference: String = runMode("control")
  private def runMode(mode: String): String = {
    val parent                 = Files.createDirectories(Paths.get("test_run_dir", suiteName))
    val output                 = Files.createTempDirectory(parent, mode + "-").toAbsolutePath
    val p                      = (new firesim.configs.DefaultF1Config).alterPartial {
      case HostPayloadHoldEnabled           => mode == "payload" || mode == "all"
      case HostTriggerSnapshotEnabled       => mode == "trigger" || mode == "all"
      case HostTargetOutputIsolationEnabled => mode == "isolation" || mode == "all"
      case HostClockGateModuleName          => "V80SlashClockGate"
      case OutputDir                        => output.toFile
    }
    val (circuit, annotations) = midas.ElaborateChiselSubCircuit(new HostTimingHarness)
    val input                  = annotations ++ Seq(
      FirrtlCircuitAnnotation(circuit),
      ConfigParametersAnnotation(p),
      TargetDirAnnotation(output.toString),
      OutputBaseFilenameAnnotation("HostTiming"),
    )
    val result                 = new GoldenGateCompilerPhase().transform(AddDerivedAnnotations.transform(input))
    val rtl                    = result.collect { case firrtl.EmittedVerilogCircuitAnnotation(c) => c.value }.mkString
    val header                 = result.collectFirst { case GoldenGateOutputFileAnnotation(body, ".const.h") => body }.get
    Files.writeString(output.resolve("HostTiming.sv"), rtl)
    Files.writeString(output.resolve("HostTiming.const.h"), header)
    if (mode == "payload" || mode == "all")
      assert(rtl.contains("host_held_peekPokeBridge_seed"), "Payload register missing")
    if (mode == "trigger" || mode == "all") assert(rtl.contains("host_held_trigger"), "Trigger snapshot missing")
    if (mode == "isolation" || mode == "all")
      assert(rtl.contains("HostTargetOutputIsolator"), "Output buffers missing")
    def register(name: String): String = ("\\." + name + " = ([0-9]+)").r.findFirstMatchIn(header).get.group(1)
    def port(name:     String): String = ("R\"ESC\\(" + name + "\\)ESC\", peek_poke_t::Port\\{\\.address = ([0-9]+)ULL").r
      .findFirstMatchIn(header)
      .get
      .group(1)
    val constants =
      Seq("INIT_DONE", "STEP", "DONE", "PRECISE_PEEKABLE").map(n => s"#define ADDR_$n ${register(n)}") ++
        Seq(
          "reset",
          "seed",
          "enable",
          "fast",
          "slow",
          "last",
          "sum",
          "mixed",
          "signed",
          "triggerFast",
          "triggerSlow",
          "triggerThird",
        ).map(n => s"#define ADDR_${n.toUpperCase} ${port(n)}")
    Files.writeString(output.resolve("timing_test_addrs.h"), constants.mkString("\n") + "\n")
    def run(command: Seq[String], name: String): Int = {
      Files.writeString(output.resolve(name + "-command.txt"), command.mkString("\n"))
      val out = new PrintWriter(output.resolve(name + ".stdout").toFile)
      val err = new PrintWriter(output.resolve(name + ".stderr").toFile)
      try Process(command, output.toFile).!(ProcessLogger(out.println(_), err.println(_)))
      finally { out.close(); err.close() }
    }
    val obj       = output.resolve("obj")
    val isolator  = output.resolve("HostTargetOutputIsolator.v")
    if (mode == "isolation" || mode == "all") Files.copy(Paths.get("HostTargetOutputIsolator.v"), isolator)
    val build     = Seq(
      "verilator",
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
      output.resolve("HostTiming.sv").toString,
      Paths.get("../platforms/xilinx_alveo_v80_slash/cl_firesim/design/V80SlashClockGate.v").toAbsolutePath.toString,
      Paths.get("midas/src/test/rtl/slash_primitives.sv").toAbsolutePath.toString,
      Paths.get("midas/src/test/cc/host_timing_test.cc").toAbsolutePath.toString,
    ) ++
      (if (Files.exists(isolator)) Seq(isolator.toString) else Seq.empty)
    println(s"Host timing regression output: $output")
    assert(run(build, "build") == 0, s"Verilator build failed; see $output")
    assert(run(Seq(obj.resolve("VFPGATop").toString), "simulate") == 0, s"Simulation failed; see $output")
    Files.readString(output.resolve("target-observations.csv"))
  }
  for (mode <- Seq("control", "payload", "trigger", "isolation", "all")) {
    it should s"preserve multirate arithmetic and trigger observations with $mode" in {
      val expected = reference
      val actual   = if (mode == "control") expected else runMode(mode)
      assert(actual.equals(expected), s"Target-cycle observations changed in $mode; inspect target-observations.csv")
    }
  }
}
