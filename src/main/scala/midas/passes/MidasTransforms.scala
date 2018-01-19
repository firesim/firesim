// See LICENSE for license details.

package midas
package passes

import midas.core._

import chisel3.experimental.ChiselAnnotation

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
    dir: File,
    io: Seq[chisel3.Data])
    (implicit param: freechips.rocketchip.config.Parameters) extends Transform {
  def inputForm = LowForm
  def outputForm = LowForm
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

// FIXME - get from C3
trait DontTouchAnnotator { // scalastyle:ignore object.name
  this: chisel3.Module =>

  def dontTouch[T <: chisel3.Data](data: T): T = {
    // TODO unify with firrtl.transforms.DontTouchAnnotation
    annotate(ChiselAnnotation(data, classOf[firrtl.Transform], "DONTtouch!"))
    data
  }
}

// Mixed into modules that contain instances that will be Fame1 tranformed
trait Fame1Annotator {
  this: chisel3.Module =>

  // Transforms a single instance; targetFire should be set to the name of a Bool
  // that will be used to tick the module
  def fame1transform(module: chisel3.Module, targetFire: String): Unit = {
    annotate(ChiselAnnotation(module, classOf[DedupModules], "nodedup!"))
    annotate(ChiselAnnotation(module, classOf[Fame1Instances], targetFire))
  }

  // Takes a series of modules; uses a bool named "targetFire" in enclosing context
  def fame1transform(modules: chisel3.Module*): Unit = modules.foreach(fame1transform(_, "targetFire"))
}

object Fame1Annotation {
  def apply(target: ModuleName, tFire: String): Annotation = Annotation(target, classOf[Fame1Instances], tFire)

  def unapply(a: Annotation): Option[(ModuleName, String)] = a match {
    case Annotation(ModuleName(n, c), _, tFire) => Some(ModuleName(n, c) -> tFire) 
    case _ => None
  }
}

class Fame1Instances extends Transform {
  def inputForm = LowForm
  def outputForm = HighForm
  def execute(state: CircuitState): CircuitState = {
    getMyAnnotations(state) match {
      case Nil => state.copy()
      case annos =>
      val fame1s = (annos.collect { case Fame1Annotation(ModuleName(m, c), tFire) => m -> tFire }).toMap
      state.copy(circuit = new ModelFame1Transform(fame1s).run(state.circuit))
    }
  }
}

