package midas
package passes

import midas.core._
import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.annotations._
import Utils._
import MidasTransforms._
import scala.collection.mutable.{HashMap, LinkedHashSet, ArrayBuffer}
import scala.util.DynamicVariable
import java.io.{File, FileWriter}

object MidasTransforms {
  type ChainMap = HashMap[String, ArrayBuffer[ir.Statement]]
  type ChildMods = HashMap[String, LinkedHashSet[String]]
  type ChildInsts = HashMap[String, ArrayBuffer[String]]
  type InstModMap = HashMap[(String, String), String]
}

private class WCircuit(
  info: Info,
  modules: Seq[DefModule],
  main: String,
  val sim: SimWrapperIO) extends Circuit(info, modules, main)

class TransformAnalysis(
    childMods: ChildMods,
    childInsts: ChildInsts,
    instModMap: InstModMap) extends firrtl.passes.Pass {
  override def name = "[midas] Analyze Circuit"

  def collectChildren(mname: String, blackboxes: Set[String])(s: Statement): Statement = {
    s match {
      case s: WDefInstance if !blackboxes(s.module) =>
        childMods(mname) += s.module
        childInsts(mname) += s.name
        instModMap(s.name -> mname) = s.module
      case _ =>
    }
    s map collectChildren(mname, blackboxes)
  }

  def collectChildrenMod(blackboxes: Set[String])(m: DefModule) = {
    childInsts(m.name) = ArrayBuffer[String]()
    childMods(m.name) = LinkedHashSet[String]()
    m map collectChildren(m.name, blackboxes)
  }

  def run(c: Circuit) = {
    val blackboxes = (c.modules collect { case m: ExtModule => m.name }).toSet
    c copy (modules = c.modules map collectChildrenMod(blackboxes))
  }
}

object MidasAnnotation {
  def apply(t: String, conf: File) =
    Annotation(CircuitName(t), classOf[MidasTransforms], conf.toString)
  def unapply(a: Annotation) = a match {
    case Annotation(CircuitName(t), transform, conf) if transform == classOf[MidasTransforms] =>
      Some(CircuitName(t), new File(conf))
    case _ => None
  }
}

private[midas] class MidasTransforms(
    dir: File,
    io: chisel3.Data)
   (implicit param: config.Parameters) extends Transform {
  val childMods = new ChildMods
  val childInsts = new ChildInsts
  val instModMap = new InstModMap
  val chains = (ChainType.values.toList map (_ -> new ChainMap)).toMap

  def inputForm = MidForm
  def outputForm = MidForm
  def execute(state: CircuitState) = (getMyAnnotations(state): @unchecked) match {
    case Seq(MidasAnnotation(CircuitName(state.circuit.main), conf)) =>
      val seqMems = (MemConfReader(conf) map (m => m.name -> m)).toMap
      val transforms = Seq(
        new Fame1Transform(seqMems),
        new TransformAnalysis(childMods, childInsts, instModMap),
        new AddDaisyChains(childMods, childInsts, instModMap, chains, seqMems),
        new SimulationMapping(io, dir, childInsts, instModMap, chains, seqMems),
        new PlatformMapping(state.circuit.main, dir)
      )
      (transforms foldLeft state)((in, xform) => xform runTransform in) copy (form=outputForm)
  }
}
