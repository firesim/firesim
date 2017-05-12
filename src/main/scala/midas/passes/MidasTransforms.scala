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
      Some(CircuitName(t), new java.io.File(conf))
    case _ => None
  }
}

private[midas] class MidasTransforms(
    dir: java.io.File,
    io: chisel3.Data)
   (implicit param: config.Parameters) extends Transform {
  def inputForm = MidForm
  def outputForm = MidForm
  def execute(state: CircuitState) = (getMyAnnotations(state): @unchecked) match {
    case Seq(MidasAnnotation(CircuitName(state.circuit.main), conf)) =>
      val seqMems = (MemConfReader(conf) map (m => m.name -> m)).toMap
      val stroberPasses = param(EnableSnapshot) match {
        case true => Seq(new strober.passes.StroberTransforms(dir, seqMems))
        case false => Nil
      }
      val transforms = Seq(
        new Fame1Transform(seqMems)) ++
        stroberPasses ++ Seq(
        new SimulationMapping(io),
        new PlatformMapping(state.circuit.main, dir)
      )
      (transforms foldLeft state)((in, xform) => xform runTransform in).copy(form=outputForm)
  }
}
