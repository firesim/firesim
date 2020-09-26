// See LICENSE for license details.

package midas.passes

import firrtl._
import firrtl.ir._
import firrtl.annotations._
import firrtl.options.Dependency
import firrtl.passes.MemPortUtils._

case class ReversibleMemBlackBoxAnnotation(target: ModuleTarget, mem: DefMemory) extends SingleTargetAnnotation[ModuleTarget] {
  override def duplicate(newTarget: ModuleTarget): ReversibleMemBlackBoxAnnotation = this.copy(target = newTarget)
}

object ReversiblyBlackBoxMems extends Transform with DependencyAPIMigration {
  override def prerequisites = Nil
  override def optionalPrerequisites = Nil
  override def optionalPrerequisiteOf = Seq(
    Dependency(firrtl.passes.LowerTypes),
    Dependency[firrtl.passes.memlib.ReplSeqMem]
  )

  override def invalidates(a: Transform): Boolean = false

  private type BBAnno = ReversibleMemBlackBoxAnnotation

  private def memToPorts(mem: DefMemory): Seq[Port] = {
    memType(mem).fields.map {
      case Field(name, Flip, tpe) => Port(NoInfo, name, Input, tpe)
    }
  }

  private def onStmt(mt: ModuleTarget, ns: Namespace)(s: Statement): (Statement, Seq[BBAnno]) = {
    s match {
      case Block(stmts) =>
        val (stmtsX, annosX) = stmts.map(s => onStmt(mt, ns)(s)).unzip
        (Block(stmtsX), annosX.flatten)
      case Conditionally(info, pred, cons, alt) =>
        val (consX, consAnnos) = onStmt(mt, ns)(cons)
        val (altX, altAnnos) = onStmt(mt, ns)(alt)
        (Conditionally(info, pred, consX, altX), consAnnos ++: altAnnos)
      case mem: DefMemory =>
        val bbName = ns.newName(mem.name)
        val memAnno = new BBAnno(mt.copy(module = bbName), mem)
        (WDefInstance(mem.info, mem.name, bbName, memType(mem)), Seq(memAnno))
      case s => (s, Nil)
    }
  }

  override def execute(state: CircuitState): CircuitState = {
    val circuitNS = Namespace(state.circuit)
    val (modulesX, bbAnnosPerModule) = state.circuit.modules.map({
      case m: Module =>
        val (bodyX, bbAnnos) = onStmt(ModuleTarget(state.circuit.main, m.name), circuitNS)(m.body)
        (m.copy(body = bodyX), bbAnnos)
      case m => (m, Nil)
    }).unzip

    val bbAnnos = bbAnnosPerModule.flatten
    val bbMods = bbAnnos.map {
      bbAnno => ExtModule(bbAnno.mem.info, bbAnno.target.module, memToPorts(bbAnno.mem), bbAnno.target.module, Nil)
    }

    state.copy(circuit = state.circuit.copy(modules = modulesX ++ bbMods), annotations = state.annotations ++ bbAnnos)
  }
}
