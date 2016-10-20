package strober
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.Utils.BoolType
import firrtl.passes.MemPortUtils.memPortField
import WrappedType.wt
import StroberTransforms._
import Utils._

private[passes] class Fame1Transform(
    childMods: ChildMods,
    seqMems: Map[String, MemConf]) extends firrtl.passes.Pass {
  def name = "[strober] Fame1 Transforms"
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
      s
    case s: Print =>
      s copy (en = and(s.en, targetFire))
    case s: Stop =>
      s copy (en = and(s.en, targetFire))
    case s: Connect => s.loc match {
      case e: WSubField if wt(e.tpe) == WrappedBool && ens(e.serialize) =>
        s copy (expr = or(and(s.expr, targetFire), daisyReset))
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

  private def connectTargetFire(s: Statement): Statement = s match {
    case s: WDefInstance if s.name == "target" => Block(Seq(s,
      Connect(NoInfo, wsub(wref("target"), "targetFire"), wref("fire", BoolType)),
      Connect(NoInfo, wsub(wref("target"), "daisyReset"), wref("reset", BoolType))
    ))
    case s => s map connectTargetFire
  }

  def run(c: Circuit) = c copy (modules = {
    val modMap = (wrappers(c.modules) foldLeft Map[String, DefModule]()){
      (map, m) => map ++ (
        (preorder(targets(m, c.modules), c.modules, childMods)(transform) :+
        (m map connectTargetFire)) map (m => m.name -> m)
      )
    }
    c.modules map (m => modMap getOrElse (m.name, m))
  })
}
