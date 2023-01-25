
// See LICENSE for license details.

package midas.passes

import firrtl._
import firrtl.Mappers._
import firrtl.ir._
import firrtl.options.Dependency

/**
  * Pushes enable expressions into separate nodes that can be consistently
  * optimized across by CSE. This ensures that associated pairs of stops and
  * printfs will have references to a common enable node, which allows
  * AssertionSynthesis to correctly group and synthesize them.
  *
  */

object HoistStopAndPrintfEnables extends Transform with DependencyAPIMigration {
  override def prerequisites          = Nil
  override def optionalPrerequisites  = Seq(Dependency(firrtl.transforms.formal.ConvertAsserts))
  override def optionalPrerequisiteOf = Seq(Dependency(firrtl.passes.CommonSubexpressionElimination))
  override def invalidates(a: Transform): Boolean         = false

  def onModule(m: DefModule): DefModule = {
    val namespace = Namespace(m)

    def hoistEnable(enable: Expression, stmtUpdater: Expression => Statement): Statement = {
      val hoistedEnable = DefNode(NoInfo, namespace.newTemp, enable)
      Block(hoistedEnable, stmtUpdater(Reference(hoistedEnable)))
    }

    def onStmt(s: Statement): Statement = s.map(onStmt) match {
      case stop@Stop(_,_,_,en: DoPrim)     => hoistEnable(en, (e: Expression) => stop. copy(en = e))
      case print@Print(_,_,_,_,en: DoPrim) => hoistEnable(en, (e: Expression) => print.copy(en = e))
      case o => o
    }

    m match {
      case mod: Module => mod.copy(body = mod.body.map(onStmt))
      case ext: ExtModule => ext
    }
  }

  def execute(state: CircuitState): CircuitState = {
    val c = state.circuit
    state.copy(circuit = c.copy(modules = c.modules.map(onModule)))
  }
}
