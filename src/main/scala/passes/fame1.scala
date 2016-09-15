package strober
package passes

import Utils._
import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.Utils.BoolType
import firrtl.passes.MemPortUtils.memPortField
import WrappedType.wt
import scala.collection.mutable.{ArrayBuffer, HashSet, HashMap}

private[passes] object Fame1Transform extends firrtl.passes.Pass {
  def name = "[strober] Fame1 Transforms"
  type Enables = collection.mutable.HashSet[String]
  type Statements = collection.mutable.ArrayBuffer[Statement]

  private val targetFirePort = Port(NoInfo, "targetFire", Input, BoolType)
  private val daisyResetPort = Port(NoInfo, "daisyReset", Input, BoolType)
  private val targetFire = wref(targetFirePort.name, targetFirePort.tpe)
  private val daisyReset = wref(daisyResetPort.name, daisyResetPort.tpe)

  private def collect(ens: Enables)(s: Statement): Statement = {
    s match {
      case s: DefMemory => ens ++= (
        (s.readers ++ s.writers ++ s.readwriters)
        map (memPortField(s, _, "en").serialize)
      )
      case _ =>
    }
    s map collect(ens)
  }

  private val WrappedBool = wt(BoolType)
  private def connect(ens: Enables, stmts: Statements)(s: Statement): Statement = s match {
    case s: WDefInstance =>
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
        s copy (expr = and(s.expr, targetFire))
      case _ => s
    }
    case s => s map connect(ens, stmts)
  }

  private def transform(m: DefModule): DefModule = {
    val ens = new Enables
    val stmts = new Statements
    m map collect(ens) map connect(ens, stmts) match {
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
    val modMap = (wrappers(c.modules) foldLeft Map[String, DefModule]()){ (map, m) =>
      map ++ (
        (preorder(targets(m, c.modules), c.modules)(transform) :+
        (m map connectTargetFire)) map (m => m.name -> m)
      )
    }
    c.modules map (m => modMap getOrElse (m.name, m))
  })
}
