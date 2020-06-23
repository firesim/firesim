// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import firrtl.ir._
import firrtl.Utils.{BoolType}
import midas.widgets.HasTimestampConstants

import scala.collection.mutable

object TimestampRegister extends HasTimestampConstants {
  def apply(name: String)(
      implicit ns: Namespace,
      hostReset: HostReset,
      hostclock: HostClock): DefRegister = {
    HostRegister(name, UIntType(IntWidth(64)), Some(UIntLiteral(0)))
  }
}

class ClockScheduler(
    ch: FAME1ClockChannel,
    advance: Expression,   // Bool
    advanceToTime: Expression // UInt of timestamp width
  )(implicit ns: Namespace,
    hostReset: HostReset,
    hostclock: HostClock) {

  val stmts = new mutable.ArrayBuffer[Statement]()
  def connect(loc: Expression, expr: Expression): Unit = {
    stmts += Connect(NoInfo, loc, expr)
  }

  def node(suffix: String, expr: Expression): WRef = {
    val node = DefNode(NoInfo, ns.newName(name(suffix)), expr)
    stmts += node
    WRef(node)
  }

  def name(suffix: String): String = ns.newName(s"${ch.name}_${suffix}")
  val oldValidReg = HostFlagRegister(name("oldValid"))
  val oldTimeReg  = TimestampRegister(name("time"))
  val oldDataReg  = HostRegister(name("data"), UIntType(IntWidth(ch.ports.size)))
  stmts ++= Seq(oldValidReg, oldTimeReg, oldDataReg)

  def oldValid = WRef(oldValidReg)
  def oldTime = WRef(oldTimeReg)
  def oldData = WRef(oldDataReg)
  def nextValid = ch.isValid
  def nextTime = ch.getTimestampRef
  def nextData = DoPrim(PrimOps.AsUInt, Seq(ch.payloadRef), Nil, BoolType)
  def defined = oldValid

  val definedUntil = node("definedUntil", Mux(nextValid, nextTime, oldTime))
  val posedge = node("posedge", Seq(nextValid, oldValid, Negate(oldData), nextData).reduce(And.apply))
  val posedgeEnable = node("posedgeEnable", And(advance, Eq(nextTime, advanceToTime)))
  val advanceLocally = node("advanceLocally", Mux(posedge, posedgeEnable, nextValid))

  connect(oldValid, Or(oldValid, ch.isValid))
  connect(oldTime, Mux(advanceLocally, nextTime, oldTime))
  connect(oldData, Mux(advanceLocally, nextData, oldData))
  stmts += ch.setReady(advanceLocally)
}
