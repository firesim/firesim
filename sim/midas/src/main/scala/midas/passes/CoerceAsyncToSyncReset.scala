// See LICENSE for license details

package midas.passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.Utils.BoolType

/**
  * Replaces all AsyncResets and Reset types with Bools (synchronous reset).
  *
  */

object CoerceAsyncToSyncReset extends firrtl.Transform {
  override def name = "[Golden Gate] Coerce AsyncReset to SyncReset"
  def inputForm = LowForm
  def outputForm = LowForm

  private def onType(t: Type): Type = t match {
    case AsyncResetType => BoolType
    case ResetType => BoolType
    case o => o
  }
  private def onExpr(e: Expression): Expression = e.map(onExpr).map(onType) match {
    case p @ DoPrim(PrimOps.AsAsyncReset, Seq(arg), _, _) => p.copy(op = PrimOps.AsUInt, tpe = BoolType)
    case o => o
  }
  private def onStmt(s: Statement): Statement = s.map(onStmt).map(onExpr).map(onType)
  private def onPort(p: Port): Port = p.copy(tpe = onType(p.tpe))
  private def onModule(m: DefModule): DefModule = m.map(onPort).map(onStmt)
  def execute(state: CircuitState): CircuitState = state.copy(circuit = state.circuit.map(onModule))
}
