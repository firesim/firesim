// See LICENSE for license details.

package strober
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.annotations._
import core.ChainType
import mdf.macrolib.SRAMMacro
import mdf.macrolib.Utils.readMDFFromString
import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet, LinkedHashSet}

object StroberMetaData {
  private def collectChildren(
      mname: String,
      meta: StroberMetaData,
      blackboxes: Set[String])
     (s: Statement): Statement = {
    s match {
      case s: WDefInstance if !blackboxes(s.module) =>
        meta.childMods(mname) += s.module
        meta.childInsts(mname) += s.name
        meta.instModMap(s.name -> mname) = s.module
      case _ =>
    }
    s map collectChildren(mname, meta, blackboxes)
  }

  private def collectChildrenMod(
      meta: StroberMetaData,
      blackboxes: Set[String])
     (m: DefModule) = {
    meta.childInsts(m.name) = ArrayBuffer[String]()
    meta.childMods(m.name) = LinkedHashSet[String]()
    m map collectChildren(m.name, meta, blackboxes)
  }

  def apply(c: Circuit) = {
    val meta = new StroberMetaData
    val blackboxes = (c.modules collect { case m: ExtModule => m.name }).toSet
    c.modules map collectChildrenMod(meta, blackboxes)
    meta
  }
}

class StroberMetaData {
  type ChainMap = HashMap[String, ArrayBuffer[ir.Statement]]
  type ChildMods = HashMap[String, LinkedHashSet[String]]
  type ChildInsts = HashMap[String, ArrayBuffer[String]]
  type InstModMap = HashMap[(String, String), String]

  val childMods = new ChildMods
  val childInsts = new ChildInsts
  val instModMap = new InstModMap
  val chains = (ChainType.values.toList map (_ -> new ChainMap)).toMap
}

object preorder {
  def apply(c: Circuit,
            meta: StroberMetaData)
           (visit: DefModule => DefModule): Seq[DefModule] = {
    val head = (c.modules find (_.name == c.main)).get
    val visited = HashSet[String]()
    def loop(m: DefModule): Seq[DefModule] = {
      visited += m.name
      visit(m) +: (c.modules filter (x =>
        meta.childMods(m.name)(x.name) && !visited(x.name)) flatMap loop)
    }
    loop(head) ++ (c.modules collect { case m: ExtModule => m })
  }
}

object postorder {
  def apply(c: Circuit,
            meta: StroberMetaData)
           (visit: DefModule => DefModule): Seq[DefModule] = {
    val head = (c.modules find (_.name == c.main)).get
    val visited = HashSet[String]()
    def loop(m: DefModule): Seq[DefModule] = {
      val res = (c.modules filter (x =>
        meta.childMods(m.name)(x.name)) flatMap loop)
      if (visited(m.name)) {
        res
      } else {
        visited += m.name
        res :+ visit(m)
      }
    }
    loop(head) ++ (c.modules collect { case m: ExtModule => m })
  }
}

class StroberTransforms(
    dir: java.io.File,
    json: java.io.File)
   (implicit param: freechips.rocketchip.config.Parameters) extends Transform {
  def inputForm = LowForm
  def outputForm = LowForm
  def execute(state: CircuitState) = {
    if (param(midas.EnableSnapshot)) {
      lazy val srams = {
        val str = io.Source.fromFile(json).mkString
        val srams = readMDFFromString(str).get collect { case x: SRAMMacro => x }
        (srams map (sram => sram.name -> sram)).toMap
      }
      val meta = StroberMetaData(state.circuit)
      val xforms = Seq(
        new AddDaisyChains(meta, srams),
        new DumpChains(dir, meta, srams))
      (xforms foldLeft state)((in, xform) =>
        xform runTransform in).copy(form=outputForm)
    } else {
      state
    }
  }
}
