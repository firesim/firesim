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

trait Scheduler {
  def ch: FAME1Channel with HasModelPort with InputChannel with TimestampedChannel
  def advance: Expression   // Bool
  def advanceToTime: Expression // UInt of timestamp width
  implicit val ns: Namespace
  implicit val hostReset: HostReset
  implicit val hostclock: HostClock

  require(ch.ports.size == 1)
  def payloadUIntType = ch.ports.head.tpe match {
    case g: GroundType => UIntType(g.width)
    case o => throw new Exception(s"Expect only GroundTypes in timestamped channels. Got $o")
  }

  def payloadNativeType = ch.ports.head.tpe

  val decls = new mutable.ArrayBuffer[Statement]()
  val conns = new mutable.ArrayBuffer[Statement]()
  def connect(loc: Expression, expr: Expression): Unit = conns += Connect(NoInfo, loc, expr)

  def node(suffix: String, expr: Expression): WRef = {
    val node = DefNode(NoInfo, ns.newName(name(suffix)), expr)
    decls += node
    WRef(node)
  }
  def wire(suffix: String, tpe: Type): WRef = {
    val wire = DefWire(NoInfo, ns.newName(name(suffix)), tpe)
    decls += wire
    WRef(wire)
  }

  def name(suffix: String): String = ns.newName(s"${ch.name}_${suffix}")

  val oldValidReg = HostFlagRegister(name("oldValid"))
  val oldTimeReg  = TimestampRegister(name("time"))
  val oldDataReg  = HostRegister(name("data"), payloadUIntType)
  decls ++= Seq(oldValidReg, oldTimeReg, oldDataReg)

  def oldValid = WRef(oldValidReg)
  def oldTime = WRef(oldTimeReg)
  def oldData = WRef(oldDataReg)
  def nextValid = ch.isValid
  def nextTime = ch.getTimestampRef
  def nextData = DoPrim(PrimOps.AsUInt, Seq(ch.payloadRef), Nil, UnknownType)
  def defined = oldValid

  val definedUntil = node("definedUntil", Mux(nextValid, nextTime, oldTime))
  val timeMatch = node("timeMatch", And(advance, Eq(nextTime, advanceToTime)))
  val advanceLocally = wire("advanceLocally", BoolType)
  //val advanceLocally = wire("advanceLocally", Mux(posedge, timeMatch, nextValid))

  connect(oldValid, Or(oldValid, ch.isValid))
  connect(oldTime, Mux(advanceLocally, nextTime, oldTime))
  connect(oldData, Mux(advanceLocally, nextData, oldData))
  conns += ch.setReady(advanceLocally)
  lazy val stmts = decls ++ conns
}

class ClockScheduler(
    val ch: FAME1ClockChannel,
    val advance: Expression,   // Bool
    val advanceToTime: Expression // UInt of timestamp width
  )(implicit val ns: Namespace,
    val hostReset: HostReset,
    val hostclock: HostClock) extends Scheduler {

  val posedge = node("posedge", Seq(nextValid, oldValid, Negate(oldData), nextData).reduce(And.apply))
  val posedgeScheduled = node("posedgeScheduled", And(posedge, timeMatch))
  connect(advanceLocally, Mux(posedge, timeMatch, nextValid))
}

class DataScheduler(
    val ch: FAME1TimestampedInputChannel,
    val advance: Expression,   // Bool
    val advanceToTime: Expression // UInt of timestamp width
  )(implicit val ns: Namespace,
    val hostReset: HostReset,
    val hostclock: HostClock) extends Scheduler {

  val dataEdge = node("dataEdge", And(oldValid, And(nextValid, Neq(nextData, oldData))))
  val edgeScheduled = node("observableEventScheduled", And(dataEdge, timeMatch))
  connect(advanceLocally, Mux(dataEdge, timeMatch, nextValid))
}

