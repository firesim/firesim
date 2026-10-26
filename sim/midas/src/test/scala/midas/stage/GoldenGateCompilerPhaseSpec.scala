// See LICENSE for license details.

package midas.stage

import java.nio.file.Files

import chisel3._
import firrtl.{CircuitState, EmittedVerilogCircuitAnnotation, LowForm, Transform}
import firrtl.annotations.NoTargetAnnotation
import firrtl.options.{Dependency, TargetDirAnnotation}
import firrtl.stage.FirrtlCircuitAnnotation
import midas.{HostTransforms, OutputDir}
import midas.stage.phases.ConfigParametersAnnotation
import org.scalatest.flatspec.AnyFlatSpec

case object HostLoweringCompleted extends NoTargetAnnotation

/** Test transform whose annotation must survive the remaining compiler stages. */
class RecordHostLowering extends Transform {
  def inputForm  = LowForm
  def outputForm = LowForm
  def execute(state: CircuitState): CircuitState =
    state.copy(annotations = state.annotations :+ HostLoweringCompleted)
}

class HostLoweringTarget extends Module {
  val io    = IO(new Bundle {
    val enable = Input(Bool())
    val count  = Output(UInt(8.W))
  })
  val count = RegInit(0.U(8.W))
  when(io.enable) { count := count + 1.U }
  io.count := count
}

class GoldenGateCompilerPhaseSpec extends AnyFlatSpec {
  behavior.of("GoldenGateCompilerPhase")

  for (recordHostLowering <- Seq(false, true)) {
    it should (if (recordHostLowering) "retain host-transform results through final emission"
               else "emit a complete simulator without an extra host transform") in {
      val parent                 = Files.createDirectories(java.nio.file.Paths.get("test_run_dir", suiteName))
      val output                 = Files.createTempDirectory(parent, if (recordHostLowering) "host-transform-" else "control-")
      val base                   = new firesim.configs.DefaultF1Config
      val transforms             = base(HostTransforms) ++
        (if (recordHostLowering) Seq(Dependency[RecordHostLowering]) else Seq.empty)
      val parameters             = base.alterPartial {
        case HostTransforms => transforms
        case OutputDir      => output.toFile
      }
      val (circuit, annotations) = midas.ElaborateChiselSubCircuit(
        new firesim.lib.testutils.PeekPokeHarness(() => new HostLoweringTarget)
      )
      val input                  = annotations ++ Seq(
        FirrtlCircuitAnnotation(circuit),
        ConfigParametersAnnotation(parameters),
        TargetDirAnnotation(output.toString),
        OutputBaseFilenameAnnotation("HostLowering"),
      )
      val result                 = new GoldenGateCompilerPhase().transform(AddDerivedAnnotations.transform(input))
      val emitted                = result.collect { case EmittedVerilogCircuitAnnotation(circuit) => circuit.value }.mkString
      assert(emitted.contains("module FPGATop"))
      Files.writeString(output.resolve("HostLowering.sv"), emitted)
      println(s"Compiler regression output: ${output.toAbsolutePath}")
      assert(result.contains(HostLoweringCompleted) == recordHostLowering)
    }
  }
}
