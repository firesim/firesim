// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import ir._
import Utils._

object Instantiate {
  def apply(m: DefModule, name: String) = WDefInstance(NoInfo, name, m.name, Utils.module_type(m))
}

object Decouple {
  def apply(t: Type): Type = BundleType(Seq(
    Field("ready", Flip, Utils.BoolType),
    Field("valid", Default, Utils.BoolType),
    Field("bits", Default, t)))
  def apply(p: Port): Port = p.copy(tpe = apply(p.tpe))
}

object IsDecoupled {
  def apply(t: BundleType): Boolean = {
    val sortedFields = t.fields.sortBy(_.name)
    val tailPattern = Seq(
      Field("ready", Utils.swap(sortedFields.head.flip), Utils.BoolType),
      Field("valid", sortedFields.head.flip, Utils.BoolType))
    sortedFields.head.name == "bits" && sortedFields.tail == tailPattern
  }
  def apply(t: Type): Boolean = t match {
    case bt: BundleType => apply(bt)
    case _ => false
  }
}

class Decoupled(inst: Expression) {
  def valid = WSubField(inst, "valid")
  def ready = WSubField(inst, "ready")
  def bits(field: String = "") = {
    if (field != "")
      WSubField(WSubField(inst, "bits"), field)
    else
      WSubField(inst, "bits")
  }
}


object Negate {
  def apply(arg: Expression): Expression = DoPrim(PrimOps.Not, Seq(arg), Seq.empty, arg.tpe)
}

sealed trait BinaryBooleanOp {
  def op: PrimOp
  def apply(l: Expression, r: Expression): DoPrim = DoPrim(op, Seq(l, r), Nil, BoolType)
  def reduce(args: Iterable[Expression]): Expression = {
    require(args.nonEmpty)
    args.tail.foldLeft(args.head){ (l, r) => apply(l, r) }
  }
}

object And extends BinaryBooleanOp {
  val op = PrimOps.And
}

object Or extends BinaryBooleanOp {
  val op = PrimOps.Or
}

object Xor extends BinaryBooleanOp {
  val op = PrimOps.Xor
}

object Neq extends BinaryBooleanOp {
  val op = PrimOps.Neq
}

object Eq extends BinaryBooleanOp {
  val op = PrimOps.Eq
}

object Lt extends BinaryBooleanOp {
  val op = PrimOps.Lt
}

/** Generates a DefRegister with no reset, relying instead on FPGA programming
  * to preset the register to 0
  */
object RegZeroPreset {
  def apply(info: Info, name: String, tpe: Type, clock: Expression): DefRegister =
    DefRegister(info, name, tpe, clock, zero, WRef(name))
  def apply(info: Info, name: String, clock: Expression): DefRegister =
    DefRegister(info, name, BoolType, clock, zero, WRef(name))
}

object ConditionalConnect {
  def apply(cond: Expression, lhs: Expression, rhs: Expression): Conditionally = {
    Conditionally(NoInfo, cond, Connect(NoInfo, lhs, rhs), EmptyStmt)
  }
}

class HostReset(val name: String)(implicit ns: Namespace) {
  assert(ns.tryName(name), "Could not use name ${name} for host reset")
  val port = Port(NoInfo, name, Input, BoolType)
  val ref: Expression = WRef(port)
}

class HostClock(val name: String)(implicit ns: Namespace) {
  assert(ns.tryName(name), "Could not use name ${name} for host clock")
  val port = Port(NoInfo, name, Input, ClockType)
  val ref: Expression = WRef(port)
}

object HostRTLImplicitConversions {
  implicit def hostResetToExpr(hr: HostReset): Expression = hr.ref
  implicit def hostClockToExpr(hc: HostClock): Expression = hc.ref
}

import HostRTLImplicitConversions._

object HostRegister {
  def apply(
      suggestedName: String,
      tpe: Type,
      resetVal: Option[Literal] = None)(
      implicit ns: Namespace,
      hostReset: HostReset,
      hostClock: HostClock): DefRegister = {
    val regName =  ns.newName(suggestedName)
    val resolvedReset: Expression = if (resetVal.nonEmpty) hostReset else zero
    val resolvedResetVal = resetVal.getOrElse(WRef(regName))
    DefRegister(NoInfo, regName, tpe, hostClock, resolvedReset, resolvedResetVal)
  }
}

object HostFlagRegister {
  def apply(name: String, resetVal: Literal = UIntLiteral(0))(
      implicit ns: Namespace,
      hostReset: HostReset,
      hostclock: HostClock): DefRegister = HostRegister(name, BoolType, Some(resetVal))
}
