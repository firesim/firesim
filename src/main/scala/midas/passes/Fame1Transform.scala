package midas
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.Utils.BoolType
import firrtl.passes.MemPortUtils.memPortField
import WrappedType.wt
import Utils._
import mdf.macrolib.SRAMMacro
import mdf.macrolib.Utils.readMDFFromString

private[passes] class Fame1Transform(json: java.io.File) extends firrtl.passes.Pass {
  override def name = "[midas] Fame1 Transforms"
  type Enables = collection.mutable.HashMap[String, Boolean]
  type Statements = collection.mutable.ArrayBuffer[Statement]
  private lazy val srams = {
    val str = io.Source.fromFile(json).mkString
    val srams = readMDFFromString(str).get collect { case x: SRAMMacro => x }
    (srams map (sram => sram.name -> sram)).toMap
  }

  private val targetFirePort = Port(NoInfo, "targetFire", Input, BoolType)
  private val daisyResetPort = Port(NoInfo, "daisyReset", Input, BoolType)
  private val targetFire = wref(targetFirePort.name, targetFirePort.tpe)
  private val daisyReset = wref(daisyResetPort.name, daisyResetPort.tpe)

  private def collect(ens: Enables)(s: Statement): Statement = {
    s match {
      case s: DefMemory =>
        ens ++= (s.readers ++ s.writers ++ s.readwriters) map (
          memPortField(s, _, "en").serialize -> false)
        ens ++= s.readwriters map (
          memPortField(s, _, "wmode").serialize -> false)
      case s: WDefInstance => srams get s.module match {
        case Some(sram) => ens ++= sram.ports flatMap (port =>
          (port.writeEnable ++ port.readEnable ++ port.chipEnable) map (en =>
            wsub(wref(s.name), en.name).serialize -> inv(en.polarity)))
        case _ =>
      }
      case _ =>
    }
    s map collect(ens)
  }

  private def connect(ens: Enables,
                      stmts: Statements)
                      (s: Statement): Statement = s match {
    case s: WDefInstance if !(srams contains s.module) =>
      Block(Seq(s,
        Connect(NoInfo, wsub(wref(s.name), "targetFire"), targetFire),
        Connect(NoInfo, wsub(wref(s.name), "daisyReset"), daisyReset)
      ))
    case s: DefRegister =>
      val regRef = wref(s.name, s.tpe)
      stmts += Conditionally(NoInfo, targetFire, EmptyStmt, Connect(NoInfo, regRef, regRef))
      s.copy(reset = and(s.reset, targetFire))
    case s: Print =>
      s.copy(en = and(s.en, targetFire))
    case s: Stop =>
      s.copy(en = and(s.en, targetFire))
    case s: Connect => s.loc match {
      case e: WSubField => ens get e.serialize match {
        case None => s
        case Some(false) =>
          s.copy(expr = and(s.expr, targetFire))
        case Some(true) => // inverted port
          s.copy(expr = or(s.expr, not(targetFire)))
      }
      case _ => s
    }
    case s => s map connect(ens, stmts)
  }

  private def transform(m: DefModule): DefModule = {
    val ens = new Enables
    val stmts = new Statements
    if (srams contains m.name) m
    else m map collect(ens) map connect(ens, stmts) match {
      case m: Module =>
        m.copy(ports = m.ports ++ Seq(targetFirePort, daisyResetPort),
               body = Block(m.body +: stmts))
      case m: ExtModule => m
    }
  }

  def run(c: Circuit) = c copy (modules = c.modules map transform)
}
