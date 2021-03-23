// See LICENSE for license details.

package midas
package passes

import midas.core._

import freechips.rocketchip.config.Parameters

import firrtl._
import firrtl.annotations._
import firrtl.ir._
import logger._
import firrtl.transforms.{DeadCodeElimination}
import Utils._

import java.io.{File, FileWriter}

private[midas] class MidasTransforms extends Transform {
  def inputForm = LowForm
  def outputForm = HighForm

  //Logger.setLevel(LogLevel.Info)
  def execute(state: CircuitState) = {
    // Optionally run if the GenerateMultiCycleRamModels parameter is set
    val p = state.annotations.collectFirst({ case midas.stage.phases.ConfigParametersAnnotation(p)  => p }).get
    val optionalTargetTransforms = if (p(GenerateMultiCycleRamModels)) Seq(
      new fame.LabelSRAMModels,
      new ResolveAndCheck,
      new EmitFirrtl("post-wrap-sram-models.fir"))
    else Seq()

    val xforms = Seq(
      firrtl.passes.RemoveValidIf,
      new firrtl.transforms.ConstantPropagation,
      firrtl.passes.SplitExpressions,
      firrtl.passes.CommonSubexpressionElimination,
      new firrtl.transforms.DeadCodeElimination,
      new firrtl.transforms.InferResets,
      firrtl.passes.CheckTypes,
      CoerceAsyncToSyncReset,
      EnsureNoTargetIO,
      new BridgeExtraction,
      new ResolveAndCheck,
      new EmitFirrtl("post-bridge-extraction.fir"),
      new fame.EmitFAMEAnnotations("post-bridge-extraction.json"),
      new HighFirrtlToMiddleFirrtl,
      new MiddleFirrtlToLowFirrtl,
      new AutoCounterTransform,
      new EmitFirrtl("post-autocounter.fir"),
      new fame.EmitFAMEAnnotations("post-autocounter.json"),
      new ResolveAndCheck,
      new AssertPass,
      new PrintSynthesis,
      new ResolveAndCheck,
      new EmitFirrtl("post-debug-synthesis.fir"),
      new fame.EmitFAMEAnnotations("post-debug-synthesis.json"),
      // All trigger sources and sinks must exist in the target RTL before this pass runs
      TriggerWiring,
      new EmitFirrtl("post-trigger-wiring.fir"),
      new fame.EmitFAMEAnnotations("post-trigger-wiring.json"),
      // We should consider moving these lower
      ChannelClockInfoAnalysis,
      UpdateBridgeClockInfo,
      fame.WrapTop,
      fame.LabelMultiThreadedInstances,
      new ResolveAndCheck,
      new EmitFirrtl("post-wrap-top.fir")) ++
    optionalTargetTransforms ++
    Seq(
      new fame.ExtractModel,
      new ResolveAndCheck,
      new EmitFirrtl("post-extract-model.fir"),
      new HighFirrtlToMiddleFirrtl,
      new MiddleFirrtlToLowFirrtl,
      fame.PromotePassthroughConnections,
      new ResolveAndCheck,
      new EmitFirrtl("post-promote-passthrough.fir"),
      new fame.EmitFAMEAnnotations("post-promote-passthrough.json"),
      new fame.FAMEDefaults,
      new EmitFirrtl("post-fame-defaults.fir"),
      new fame.EmitFAMEAnnotations("post-fame-defaults.json"),
      fame.FindDefaultClocks,
      new fame.EmitFAMEAnnotations("post-find-default-clocks.json"),
      new fame.ChannelExcision,
      new fame.EmitFAMEAnnotations("post-channel-excision.json"),
      new EmitFirrtl("post-channel-excision.fir"),
      new fame.InferModelPorts,
      new fame.EmitFAMEAnnotations("post-infer-model-ports.json"),
      new fame.FAMETransform,
      DefineAbstractClockGate,
      fame.AddRemainingFanoutAnnotations,
      new EmitFirrtl("post-fame-transform.fir"),
      new fame.EmitFAMEAnnotations("post-fame-transform.json"),
      new ResolveAndCheck,
      fame.MultiThreadFAME5Models,
      new ResolveAndCheck,
      new passes.InlineInstances,
      passes.ResolveKinds,
      new fame.EmitAndWrapRAMModels,
      new EmitFirrtl("post-gen-sram-models.fir"),
      new ResolveAndCheck,
      new SimulationMapping(state.circuit.main),
      xilinx.HostSpecialization,
      new ResolveAndCheck)
      (xforms foldLeft state)((in, xform) =>
      xform runTransform in).copy(form=outputForm)
  }
}

/**
  * An annotation on a module indicating it should be fame1 tranformed using
  * a Bool, whose name is indicated by tFire, used as the targetFire signal
  *
  * This uses the legacy non-LI-BDN F1 transform
  */
case class Fame1ChiselAnnotation(target: chisel3.RawModule, tFire: String = "targetFire")
    extends chisel3.experimental.ChiselAnnotation {
  def toFirrtl = Fame1Annotation(target.toNamed, tFire)
}

case class Fame1Annotation(target: ModuleName, tFire: String) extends
    SingleTargetAnnotation[ModuleName] {
  def duplicate(n: ModuleName) = this.copy(target = n)
}

class Fame1Instances extends Transform {
  def inputForm = MidForm
  def outputForm = HighForm
  def execute(state: CircuitState): CircuitState = {
    val fame1s = (state.annotations.collect { case Fame1Annotation(ModuleName(m, c), tFire) => m -> tFire }).toMap
    state.copy(circuit = new ModelFame1Transform(fame1s).run(state.circuit))
  }
}
