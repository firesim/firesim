// See LICENSE for license details.

package midas
package passes

import midas.core._
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

private[midas] class MidasTransforms(
    io: Seq[(String, chisel3.Data)])
    (implicit p: freechips.rocketchip.config.Parameters) extends Transform {
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
      new EnsureNoTargetIO,
      // NB: Carelessly removing this pass will break the FireSim manager as we always
      // need to generate the *.asserts file. Fix by baking into driver.
      new AssertPass(dir),
      new PrintSynthesis(dir),
      new ResolveAndCheck,
      new HighFirrtlToMiddleFirrtl,
      new MiddleFirrtlToLowFirrtl,
      new BridgeExtraction,
      new ResolveAndCheck,
      new MiddleFirrtlToLowFirrtl,
      new fame.WrapTop,
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
      new fame.ChannelExcision,
      new fame.InferModelPorts,
      new EmitFirrtl("post-channel-excision.fir"),
      new fame.FAMETransform,
      DefineAbstractClockGate,
      new EmitFirrtl("post-fame-transform.fir"),
      new ResolveAndCheck,
      new fame.EmitAndWrapRAMModels,
      new EmitFirrtl("post-gen-sram-models.fir"),
      new ResolveAndCheck) ++
    Seq(
      new SimulationMapping(io),
      new PlatformMapping(state.circuit.main, dir),
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

/* Instead of passing a data structure between pre-FAME target-transforming passes 
 * Add NoTargetAnnotations that can regenerate a chisel type from a HighForm port
 *
 * Initially i tried extending SingleTargetAnnotation but we need a LowForm
 * inner circuit to do linking (if the wrapping circuit is LowForm), and thus the target
 *  will have lost its subFields when we go to regenerate the ChiselIO.
 */
trait AddedTargetIoAnnotation[T <: chisel3.Data] extends Annotation {
  def generateChiselIO(): Tuple2[String, T]
}
