package midas
package passes

import midas.core._
import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.annotations._
import Utils._

private class WCircuit(
  info: Info,
  modules: Seq[DefModule],
  main: String,
  val sim: SimWrapperIO) extends Circuit(info, modules, main)

object MidasAnnotation {
  def apply(t: String, conf: java.io.File) =
    Annotation(CircuitName(t), classOf[MidasTransforms], conf.toString)
  def unapply(a: Annotation) = a match {
    case Annotation(CircuitName(t), transform, conf) if transform == classOf[MidasTransforms] =>
      Some(t, new java.io.File(conf))
    case _ => None
  }
}

private[midas] class MidasTransforms(
    dir: java.io.File,
    io: chisel3.Data)
   (implicit param: config.Parameters) extends Transform {
  def inputForm = LowForm
  def outputForm = LowForm
  def execute(state: CircuitState) = (getMyAnnotations(state): @unchecked) match {
    case Seq(MidasAnnotation(state.circuit.main, conf)) =>
      val xforms = Seq(
        firrtl.passes.RemoveValidIf,
        new firrtl.transforms.ConstantPropagation,
        firrtl.passes.SplitExpressions,
        firrtl.passes.CommonSubexpressionElimination,
        new firrtl.transforms.DeadCodeElimination,
        new ToSeqMems(conf),
        firrtl.passes.ResolveKinds,
        firrtl.passes.RemoveEmpty,
        new Fame1Transform,
        new strober.passes.StroberTransforms(dir),
        new SimulationMapping(io),
        new PlatformMapping(state.circuit.main, dir))
      (xforms foldLeft state)((in, xform) =>
        xform runTransform in).copy(form=outputForm)
  }
}
