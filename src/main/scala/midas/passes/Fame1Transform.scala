package midas
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.Utils.BoolType
import firrtl.passes.MemPortUtils.memPortField
import WrappedType.wt
import MidasTransforms._
import Utils._

private[passes] class Fame1Transform(seqMems: Map[String, MemConf]) extends firrtl.passes.Pass {
  override def name = "[midas] Fame1 Transforms"
  type Enables = collection.mutable.HashSet[String]
  type Statements = collection.mutable.ArrayBuffer[Statement]

  private val targetFirePort = Port(NoInfo, "targetFire", Input, BoolType)
  private val daisyResetPort = Port(NoInfo, "daisyReset", Input, BoolType)
  private val targetFire = wref(targetFirePort.name, targetFirePort.tpe)
  private val daisyReset = wref(daisyResetPort.name, daisyResetPort.tpe)

  private def collect(ens: Enables, wmodes: Enables)(s: Statement): Statement = {
    s match {
      case s: WDefInstance => seqMems get s.module match {
        case None =>
        case Some(seqMem) =>
          ens ++= (seqMem.readers.indices map (i =>
            wsub(wsub(wref(s.name, s.tpe, InstanceKind), s"R$i"), "en").serialize
          )) ++ (seqMem.readwriters.indices map (i =>
            wsub(wsub(wref(s.name, s.tpe, InstanceKind), s"RW$i"), "en").serialize
          ))
          wmodes ++= (seqMem.writers.indices map (i =>
            wsub(wsub(wref(s.name, s.tpe, InstanceKind), s"W$i"), "en").serialize
          )) ++ (seqMem.readwriters.indices map (i =>
            wsub(wsub(wref(s.name, s.tpe, InstanceKind), s"RW$i"), "wmode").serialize
          ))
      }
      case s: DefMemory =>
        ens ++= (s.readers ++ s.readwriters) map (memPortField(s, _, "en").serialize)
        wmodes ++=
          (s.writers map (memPortField(s, _, "en").serialize)) ++
          (s.readwriters map (memPortField(s, _, "wmode").serialize))
      case _ =>
    }
    s map collect(ens, wmodes)
  }

  private val WrappedBool = wt(BoolType)
  private def connect(ens: Enables,
                      wmodes: Enables,
                      stmts: Statements)
                      (s: Statement): Statement = s match {
    case s: WDefInstance if !(seqMems contains s.module) =>
      Block(Seq(s,
        Connect(NoInfo, wsub(wref(s.name), "targetFire"), targetFire),
        Connect(NoInfo, wsub(wref(s.name), "daisyReset"), daisyReset)
      ))
    case s: DefRegister =>
      val regRef = wref(s.name, s.tpe)
      stmts += Conditionally(NoInfo, targetFire, EmptyStmt, Connect(NoInfo, regRef, regRef))
      s copy (reset = and(s.reset, targetFire))
    case s: Print =>
      s copy (en = and(s.en, targetFire))
    case s: Stop =>
      s copy (en = and(s.en, targetFire))
    case s: Connect => s.loc match {
      case e: WSubField if wt(e.tpe) == WrappedBool && ens(e.serialize) =>
        s copy (expr = and(s.expr, targetFire))
      case e: WSubField if wt(e.tpe) == WrappedBool && wmodes(e.serialize) =>
        s copy (expr = and(s.expr, targetFire))
      case _ => s
    }
    case s => s map connect(ens, wmodes, stmts)
  }

  private def transform(m: DefModule): DefModule = {
    val ens = new Enables
    val wmodes = new Enables
    val stmts = new Statements
    m map collect(ens, wmodes) map connect(ens, wmodes, stmts) match {
      case m: Module =>
        m copy (ports = m.ports ++ Seq(targetFirePort, daisyResetPort),
                body = Block(m.body +: stmts))
      case m: ExtModule => m
    }
  }

  def run(c: Circuit) = c copy (modules = c.modules map transform)
}
