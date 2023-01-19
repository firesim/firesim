// See LICENSE for license details.

package midas.passes

import firrtl._
import firrtl.ir._
import firrtl.passes._
import firrtl.Mappers._
import firrtl.options.{Dependency, PreservesAll}

object RemoveTrivialPartialConnects extends Pass with PreservesAll[Transform] {

  override def prerequisites =
    Dependency(InferTypes) +: Dependency(ResolveKinds) +: stage.Forms.WorkingIR

  private def onStmt(stmt: Statement): Statement = stmt match {
    case PartialConnect(i, l, e) if (l.tpe == e.tpe) => Connect(i, l, e)
    case s => s.map(onStmt)
  }

  private def onModule(m: DefModule): DefModule = m.map(onStmt)

  def run(c: Circuit): Circuit = {
    c.map(onModule)
  }
}
