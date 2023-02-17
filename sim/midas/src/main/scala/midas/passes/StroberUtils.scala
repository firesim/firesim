// See LICENSE for license details.

package midas.passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet, LinkedHashSet}

/**
  * This file contains legacy FIRRTL features that did not exist in upstream
  * FIRRTL at the time of Strober's development. These continue to be used in:
  *   AssertionSynthesis
  */

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
  type ChildMods = HashMap[String, LinkedHashSet[String]]
  type ChildInsts = HashMap[String, ArrayBuffer[String]]
  type InstModMap = HashMap[(String, String), String]

  val childMods = new ChildMods
  val childInsts = new ChildInsts
  val instModMap = new InstModMap
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
