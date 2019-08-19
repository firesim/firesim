// See LICENSE for license details.

package midas
package passes

import firrtl._
import firrtl.annotations._
import firrtl.analyses.InstanceGraph
import firrtl.ir._
import firrtl.Mappers._
import firrtl.Utils.BoolType
import firrtl.transforms.{DedupModules}
import firrtl.passes.MemPortUtils.memPortField
import WrappedType.wt
import Utils._
import mdf.macrolib.SRAMMacro
import mdf.macrolib.Utils.readMDFFromString

import chisel3.experimental.ChiselAnnotation

// Datastructures
import scala.collection.mutable

private[passes] class Fame1Transform extends firrtl.passes.Pass {
  override def name = "[midas] Fame1 Transforms"
  type Enables = collection.mutable.HashMap[String, Boolean]
  type Statements = collection.mutable.ArrayBuffer[Statement]
  private val targetFirePort = Port(NoInfo, "targetFire", Input, BoolType)
  private val targetFire = wref(targetFirePort.name, targetFirePort.tpe)

  private def collect(ens: Enables)(s: Statement): Statement = {
    s match {
      case s: DefMemory =>
        ens ++= (s.readers ++ s.writers ++ s.readwriters) map (
          memPortField(s, _, "en").serialize -> false)
        ens ++= s.readwriters map (
          memPortField(s, _, "wmode").serialize -> false)
      case _ =>
    }
    s map collect(ens)
  }

  private def connect(ens: Enables,
                      stmts: Statements)
                      (s: Statement): Statement = s match {
    case s: WDefInstance =>
      Block(Seq(s,
        Connect(NoInfo, wsub(wref(s.name), "targetFire"), targetFire)
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

  protected def transform(m: DefModule): DefModule = {
    val ens = new Enables
    val stmts = new Statements
    m map collect(ens) map connect(ens, stmts) match {
      case m: Module =>
        m.copy(ports = m.ports ++ Seq(targetFirePort),
               body = Block(m.body +: stmts))
      case m: ExtModule => m
    }
  }

  def run(c: Circuit) = c copy (modules = c.modules map transform)
}

// This variety of fame1 transform is apply to subtrees of the instance hiearchy
// It is used to tranform RTL used as a timing model within handwritten models
// where it may be easier to simply write target RTL
class ModelFame1Transform(f1Modules: Map[String, String], f1ModuleSuffix: String = "_f1")
    extends Fame1Transform {

  private val duplicateModuleSuffix = "_f1"

  class F1ExtModException extends Exception("Unexpected Black Box in FAME1 hierarchy") 

  // Maps all instances within a FAME1 module to point to new F1 duplicates
  private def renameInstancesS(s: Statement): Statement = s match {
    case s: WDefInstance => WDefInstance(s.name, s.module + duplicateModuleSuffix)
    case s => s map renameInstancesS
  }

  private def renameInstances(m: DefModule): DefModule = m match {
    case m: Module => m.copy(body = m.body map renameInstancesS)
    case m: ExtModule => throw new F1ExtModException
  }

  // Appends a suffix to modules that will be transformed
  private def renameModules(suffix: String)(m: DefModule): DefModule = m match {
    case m: Module => m.copy(name = m.name + suffix)
    case ex: ExtModule => throw new F1ExtModException
  }

  private def bindTargetFires(m: DefModule): DefModule = {
    // Find all FAME1 modules and generate a connect for their targetFires
    def collectTargetFires(stmts: Statements)(s: Statement): Statement = s match {
      case s: WDefInstance if f1Modules.keys.toSeq.contains(s.module) =>
        val targetFireName = f1Modules(s.module)
        stmts += Connect(NoInfo, wsub(wref(s.name), "targetFire"), wref(targetFireName, UnknownType))
        s
      case s => s map collectTargetFires(stmts)
    }

    m match {
      case s: Module  =>
        val targetFireConnects = new Statements
        s map collectTargetFires(targetFireConnects)
        s.copy(body = Block(s.body +: targetFireConnects))
      case s => s
    }
  }

  override def run(c: Circuit): Circuit = {
    val moduleMap = c.modules.map(m => m.name -> m).toMap
    val modsToDup = mutable.HashSet[DefModule]()
    val modsToKeep = mutable.HashSet[DefModule]()

    def getF1Modules(inF1Context: Boolean, s: Statement): Unit = s match {
      case s: WDefInstance =>
        moduleMap(s.module) match {
          case m: Module  =>
            val isF1Root = f1Modules.keys.toSeq.contains(m.name)
            if (inF1Context) {
              modsToDup += m
            } else if (!isF1Root) {
              modsToKeep += m
            }
            getF1Modules(inF1Context || isF1Root, m.body)
          case m: ExtModule if inF1Context => throw new F1ExtModException
          case m: ExtModule =>
            modsToKeep += m
        }
      case s: Block => s.stmts.foreach(getF1Modules(inF1Context, _))
      case _ => Nil
    }

    moduleMap(c.main) match {
      case m: Module if f1Modules.keys.toSeq.contains(c.main) =>
        c.copy(modules = c.modules map transform)
      case m: Module =>
        getF1Modules(false, m.body)
        val fame1ChildModules = c.modules filter modsToDup.contains map
          renameModules(f1ModuleSuffix) map renameInstances
        val fame1RootModules = f1Modules.keys map moduleMap map renameInstances
        val fame0Modules = c.modules filter modsToKeep.contains map bindTargetFires
        val fame1TransformedModules = (fame1ChildModules ++ fame1RootModules).toList map transform
        c.copy(modules = Seq(m.copy()) ++ fame0Modules ++ fame1TransformedModules)
      case _ => throw new RuntimeException("Should not have an ExtModule as top.")
    }
  }
}
