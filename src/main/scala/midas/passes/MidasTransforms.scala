// See LICENSE for license details.

package midas
package passes

import midas.core._

import firrtl._
import firrtl.annotations._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.transforms.{DedupModules, DeadCodeElimination}
import Utils._
import java.io.{File, FileWriter}

private class WCircuit(
  info: Info,
  modules: Seq[DefModule],
  main: String,
  val sim: SimWrapperIO) extends Circuit(info, modules, main)

object MidasAnnotation {
  def apply(t: String, conf: File, json: File, lib: Option[File]) =
    Annotation(CircuitName(t), classOf[MidasTransforms],
               s"$conf $json %s".format(lib map (_.toString) getOrElse ""))
  private val matcher = "([^ ]+) ([^ ]+) ([^ ]*)".r
  def unapply(a: Annotation) = a match {
    case Annotation(CircuitName(c), t, matcher(conf, json, lib))
      if t == classOf[MidasTransforms] =>
        Some(c, new File(conf), new File(json), if (lib.isEmpty) None else Some(new File(lib)))
    case _ => None
  }
}

private[midas] class MidasTransforms(
    io: Seq[chisel3.Data])
    (implicit param: freechips.rocketchip.config.Parameters) extends Transform {
  def inputForm = LowForm
  def outputForm = LowForm
  val dir = param(OutputDir)
  def execute(state: CircuitState) = (getMyAnnotations(state): @unchecked) match {
    case Seq(MidasAnnotation(state.circuit.main, conf, json, lib)) =>
      val xforms = Seq(
        firrtl.passes.RemoveValidIf,
        new firrtl.transforms.ConstantPropagation,
        firrtl.passes.SplitExpressions,
        firrtl.passes.CommonSubexpressionElimination,
        new firrtl.transforms.DeadCodeElimination,
        new ConfToJSON(conf, json),
        new barstools.macros.MacroCompilerTransform,
        firrtl.passes.ResolveKinds,
        firrtl.passes.RemoveEmpty,
        new Fame1Transform(Some(lib getOrElse json)),
        new strober.passes.StroberTransforms(dir, lib getOrElse json),
        new SimulationMapping(io),
        new PlatformMapping(state.circuit.main, dir))
      (xforms foldLeft state)((in, xform) =>
        xform runTransform in).copy(form=outputForm)
  }
}

/**
  * An annotation on a module indicating it should be fame1 tranformed using
  * a Bool, whose name is indicated by tFire, used as the targetFire signal
  */
case class Fame1ChiselAnnotation(target: chisel3.experimental.RawModule, tFire: String = "targetFire") 
    extends chisel3.experimental.ChiselAnnotation {
  def toFirrtl = Fame1Annotation(target.toNamed, tFire)
}

case class Fame1Annotation(target: ModuleName, tFire: String) extends
    SingleTargetAnnotation[ModuleName] {
  def duplicate(n: ModuleName) = this.copy(target = n)
}

class Fame1Instances extends Transform {
  def inputForm = LowForm
  def outputForm = HighForm
  def execute(state: CircuitState): CircuitState = {
    val fame1s = (state.annotations.collect { case Fame1Annotation(ModuleName(m, c), tFire) => m -> tFire }).toMap
    state.copy(circuit = new ModelFame1Transform(fame1s).run(state.circuit))
  }
}


case class ILAWiringAnnotation(target: chisel3.core.Data) 
    extends chisel3.experimental.ChiselAnnotation {
  def toFirrtl = ILATopWiringAnnotation(target.toNamed)
}

