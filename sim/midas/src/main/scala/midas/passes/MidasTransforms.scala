// See LICENSE for license details.

package midas
package passes

import midas.core._

import freechips.rocketchip.config.Parameters
import chisel3.core.DataMirror.directionOf

import firrtl._
import firrtl.annotations._
import firrtl.ir._
import logger._
import firrtl.Mappers._
import firrtl.transforms.{DedupModules, DeadCodeElimination}
import Utils._

import java.io.{File, FileWriter}

private[passes] class WCircuit(
  info: Info,
  modules: Seq[DefModule],
  main: String,
  val sim: SimWrapperChannels) extends Circuit(info, modules, main)

private[midas] class MidasTransforms(implicit p: Parameters) extends Transform {
  def inputForm = LowForm
  def outputForm = LowForm
  val dir = p(OutputDir)

  // Optionally run if the GenerateMultiCycleRamModels parameter is set
  val optionalTargetTransforms = if (p(GenerateMultiCycleRamModels)) Seq(
    new fame.LabelSRAMModels,
    new ResolveAndCheck,
    new EmitFirrtl("post-wrap-sram-models.fir"))
  else Seq()

  //Logger.setLevel(LogLevel.Debug)
  def execute(state: CircuitState) = {
    val xforms = Seq(
      firrtl.passes.RemoveValidIf,
      new firrtl.transforms.ConstantPropagation,
      firrtl.passes.SplitExpressions,
      firrtl.passes.CommonSubexpressionElimination,
      new firrtl.transforms.DeadCodeElimination,
      EnsureNoTargetIO,
      new BridgeExtraction,
      new ResolveAndCheck,
      new EmitFirrtl("post-bridge-extraction.fir"),
      new HighFirrtlToMiddleFirrtl,
      new MiddleFirrtlToLowFirrtl,
      new AssertPass(dir),
      new PrintSynthesis(dir),
      new ResolveAndCheck,
      new HighFirrtlToMiddleFirrtl,
      new MiddleFirrtlToLowFirrtl,
      new EmitFirrtl("post-debug-synthesis.fir"),
      fame.WrapTop,
      new ResolveAndCheck,
      new EmitFirrtl("post-wrap-top.fir")) ++
    optionalTargetTransforms ++
    Seq(
      new fame.ExtractModel,
      new ResolveAndCheck,
      new EmitFirrtl("post-extract-model.fir"),
      new HighFirrtlToMiddleFirrtl,
      new MiddleFirrtlToLowFirrtl,
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
      new EmitFirrtl("post-fame-transform.fir"),
      new fame.EmitFAMEAnnotations("post-fame-transform.json"),
      new ResolveAndCheck,
      fame.MultiThreadFAME5Models,
      new EmitFirrtl("post-fame-5.fir"),
      new fame.EmitFAMEAnnotations("post-fame-5.json"),
      new ResolveAndCheck,
      new fame.EmitAndWrapRAMModels,
      new EmitFirrtl("post-gen-sram-models.fir"),
      new ResolveAndCheck) ++
    Seq(
      new SimulationMapping(state.circuit.main),
      xilinx.HostSpecialization)
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
