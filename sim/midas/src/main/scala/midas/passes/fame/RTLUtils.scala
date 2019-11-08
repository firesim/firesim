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

object Reduce {
  private def _reduce(op: PrimOp, args: Iterable[Expression]): Expression = {
    args.tail.foldLeft(args.head){ (l, r) => DoPrim(op, Seq(l, r), Seq.empty, UIntType(IntWidth(1))) }
  }
  def and(args: Iterable[Expression]): Expression = _reduce(PrimOps.And, args)
  def or(args: Iterable[Expression]): Expression = _reduce(PrimOps.Or, args)
}

