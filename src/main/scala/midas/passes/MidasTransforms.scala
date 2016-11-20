package midas
package passes

import midas.core._
import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.Annotations._
import Utils._
import MidasTransforms._
import scala.collection.mutable.{HashMap, LinkedHashSet, ArrayBuffer}
import scala.util.DynamicVariable
import java.io.{File, FileWriter}

private object MidasTransforms {
  type ChainMap = HashMap[String, ArrayBuffer[ir.Statement]]
  type ChildMods = HashMap[String, LinkedHashSet[String]]
  type ChildInsts = HashMap[String, ArrayBuffer[String]]
  type InstModMap = HashMap[(String, String), String]
}

private class WCircuit(
  info: Info,
  modules: Seq[DefModule],
  main: String,
  val sim: SimWrapperIO,
  val mem: SimMemIO) extends Circuit(info, modules, main)

private class TransformAnalysis(
    childMods: ChildMods,
    childInsts: ChildInsts,
    instModMap: InstModMap) extends firrtl.passes.Pass {
  def name = "[midas] Analyze Circuit"

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

private[midas] case class MidasAnnotation(t: String, conf: File)
    extends Annotation with Loose with Unstable {
  val target = CircuitName(t)
  def duplicate(n: Named) = this.copy(t=n.name)
  def transform = classOf[MidasTransforms]
}

private[midas] class MidasTransforms(
    dir: File,
    io: chisel3.Data)
   (implicit param: cde.Parameters) extends Transform with SimpleRun {
  val childMods = new ChildMods
  val childInsts = new ChildInsts
  val instModMap = new InstModMap
  val chains = (ChainType.values.toList map (_ -> new ChainMap)).toMap

  def inputForm = MidForm
  def outputForm = MidForm
  def execute(state: CircuitState) = (getMyAnnotations(state): @unchecked) match {
    case Seq(MidasAnnotation(t, conf)) if t == state.circuit.main =>
      val seqMems = (MemConfReader(conf) map (m => m.name -> m)).toMap
      CircuitState(runPasses(state.circuit, Seq(
        new Fame1Transform(seqMems),
        new TransformAnalysis(childMods, childInsts, instModMap),
        new AddDaisyChains(childMods, childInsts, instModMap, chains, seqMems),
        new SimulationMapping(io, dir, childInsts, instModMap, chains, seqMems),
        new PlatformMapping(state.circuit.main, dir)
      )), outputForm)
  }
}
