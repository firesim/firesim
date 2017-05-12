package midas
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.Utils.{sub_type, field_type}
import scala.collection.mutable.{ArrayBuffer, HashSet, LinkedHashSet}
import java.io.{File, FileWriter, Writer}

object Utils {
  val ut = UnknownType
  val uw = UnknownWidth
  val ug = UNKNOWNGENDER
  
  def wref(s: String, t: Type = ut, k: Kind = ExpKind) = WRef(s, t, k, ug)
  def wsub(e: Expression, s: String) = WSubField(e, s, field_type(e.tpe, s), ug)
  def widx(e: Expression, i: Int) = WSubIndex(e, i, sub_type(e.tpe), ug)
  def not(e: Expression) = DoPrim(PrimOps.Not, Seq(e), Nil, e.tpe)
  private def getType(e1: Expression, e2: Expression) = e2.tpe match {
    case UnknownType => e1.tpe
    case _ => e2.tpe
  }
  def or(e1: Expression, e2: Expression) =
    DoPrim(PrimOps.Or, Seq(e1, e2), Nil, getType(e1, e2))
  def and(e1: Expression, e2: Expression) =
    DoPrim(PrimOps.And, Seq(e1, e2), Nil, getType(e1, e2))
  def bits(e: Expression, high: BigInt, low: BigInt) =
    DoPrim(PrimOps.Bits, Seq(e), Seq(high, low), e.tpe)
  def cat(es: Seq[Expression]): Expression =
    if (es.tail.isEmpty) es.head else {
      val left = cat(es.slice(0, es.length/2))
      val right = cat(es.slice(es.length/2, es.length))
      DoPrim(PrimOps.Cat, Seq(left, right), Nil, ut)
    }

  def renameMods(c: Circuit, namespace: Namespace) = {
    val (modules, nameMap) = (c.modules foldLeft (Seq[DefModule](), Map[String, String]())){
      case ((ms, map), m: ExtModule) =>
        val newMod = (map get m.name) match {
          case None => m copy (name = namespace newName m.name)
          case Some(name) => m copy (name = name)
        }
        ((ms :+ newMod), map + (m.name -> newMod.name))
      case ((ms, map), m: Module) =>
        val newMod = (map get m.name) match {
          case None => m copy (name = namespace newName m.name)
          case Some(name) => m copy (name = name)
        }
        ((ms :+ newMod), map + (m.name -> newMod.name))
    }
    def updateModNames(s: Statement): Statement = s match {
      case s: WDefInstance => s copy (module = nameMap(s.module))
      case s => s map updateModNames
    }
    c copy (modules = modules map (_ map updateModNames), main = nameMap(c.main))
  }
}
