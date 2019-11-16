// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.Utils.{BoolType, kind, zero, one}

import collection.immutable.HashMap

// Invalidates WIR _.tpe (types of memories change)
object MultiThreader {

  type FreshNames = Map[String, Seq[String]]

  def renameRegs(freshNames: FreshNames, n: BigInt, ns: Namespace, stmt: Statement): FreshNames = stmt match {
    case Block(stmts) =>
      stmts.foldLeft(freshNames) { case (rm, s) => renameRegs(rm, n, ns, s) }
    case Conditionally(_, _, cons, alt) =>
      renameRegs(renameRegs(freshNames, n, ns, cons), n, ns, alt)
    case reg: DefRegister =>
      val regNames = (0 until n.intValue()).map(i => ns.newName(s"${reg.name}_slot_${i}"))
      freshNames.updated(reg.name, regNames)
    case s => freshNames
  }

  def replaceRegRefs(freshNames: FreshNames)(expr: Expression): Expression = expr match {
    case wr @ WRef(name, _, RegKind, FEMALE) => wr.copy(name = freshNames(name).head)
    case wr @ WRef(name, _, RegKind, MALE) => wr.copy(name = freshNames(name).last)
    case e => e
  }

  def transformDepth(depth: BigInt, n: BigInt): BigInt = {
    require(n.bitCount == 1) // pow2 threads for now
    depth * n
  }

  def transformAddr(counter: DefRegister, expr: Expression): Expression = {
    DoPrim(PrimOps.Cat, Seq(expr, WRef(counter)), Nil, UnknownType)
  }

  def multiThread(freshNames: FreshNames, n: BigInt, counter: DefRegister)(stmt: Statement): Statement = {
    val exprsReplaced = stmt map replaceRegRefs(freshNames)
    exprsReplaced match {
      case mem: DefMemory => mem.copy(depth = transformDepth(mem.depth, n))
      case reg: DefRegister =>
        val newRegs: Seq[DefRegister] = freshNames(reg.name).map(n => reg.copy(name = n))
        val paired = newRegs zip newRegs.tail
        val conns = paired.map { case (a, b) => Connect(NoInfo, WRef(b), WRef(a)) }
        Block(newRegs ++ conns)
      case conn @ Connect(info, lhs, rhs) if kind(lhs) == MemKind =>
        lhs match {
          case WSubField(p: WSubField, "addr", _, _) =>
            Connect(info, lhs, transformAddr(counter, rhs))
          case WSubField(p: WSubField, _, _, _) => conn
          case _ => throw new NotImplementedError("Cannot multithread ${stmt}. Try lowering first.")
        }
      case s => s map multiThread(freshNames, n, counter)
    }
  }

  def apply(m: Module, n: BigInt): Module = {
    val ns = Namespace(m)
    val clocks = m.ports.filter(_.tpe == ClockType)
    require(clocks.length == 1) // TODO: check for blackboxes

    val tidxMax = UIntLiteral(n)
    val tidxType = UIntType(tidxMax.width)
    val tidxRef = WRef(ns.newName("threadIdx"), tidxType, RegKind)
    val tidxDecl = DefRegister(NoInfo, tidxRef.name, tidxType, WRef(clocks.head), zero, tidxRef)
    val tidxUpdate = Mux(
      DoPrim(PrimOps.Eq, Seq(tidxRef, tidxMax), Nil, BoolType),
      UIntLiteral(0),
      DoPrim(PrimOps.Add, Seq(tidxRef, one), Nil, BoolType),
      tidxType)
    val tidxConn = Connect(NoInfo, tidxRef, tidxUpdate)

    val freshNames = renameRegs(HashMap.empty, n, ns, m.body)
    val threaded = multiThread(freshNames, n, tidxDecl)(m.body)
    m.copy(body = Block(Seq(tidxDecl, tidxConn, threaded)))
  }
}
