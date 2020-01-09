// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.traversals.Foreachers._
import firrtl.Utils.{BoolType, kind, zero, one}

import midas.passes.SignalInfo

import collection.mutable
import collection.immutable.HashMap

/* TODO:
 * 
 * This got really messy because of the need to emulate gated
 * clocks.  This involves recovering the enable from the gated clock
 * and using it to select either the output of the logic or the
 * shadowed previous value of the currently active register slot.
 * 
 * Also TODO: maybe use fewer than 200 characters per line.
 */

// Utility to help "float" instance declarations to the top of a block for convenience
object SeparateInstanceDecls {
  private def onStmt(insts: mutable.ArrayBuffer[WDefInstance])(stmt: Statement): Statement = stmt match {
    case wi: WDefInstance =>
      insts.append(wi)
      EmptyStmt
    case s => s.map(onStmt(insts))
  }

  def apply(stmt: Statement): (Seq[Statement], Statement) = {
    val insts = new mutable.ArrayBuffer[WDefInstance]
    val otherStmts = onStmt(insts)(stmt)
    (insts.toSeq, otherStmts)
  }
}

object Toggle {
  def apply(r: DefRegister): Statement = {
    Connect(FAME5Info, WRef(r), DoPrim(PrimOps.Not, Seq(WRef(r)), Nil, r.tpe))
  }
}

// Invalidates WIR _.tpe (types of memories change)
object MultiThreader {

  type FreshNames = Map[String, Seq[String]]

  def renameRegs(freshNames: FreshNames, n: BigInt, ns: Namespace, stmt: Statement): FreshNames = stmt match {
    case Block(stmts) =>
      stmts.foldLeft(freshNames) { case (rm, s) => renameRegs(rm, n, ns, s) }
    case Conditionally(_, _, cons, alt) =>
      renameRegs(renameRegs(freshNames, n, ns, cons), n, ns, alt)
    case reg: DefRegister =>
      // One extra register to shadow value for clock-gated registers
      val regNames = (0 to n.intValue()).map(i => ns.newName(s"${reg.name}_slot_${i}"))
      freshNames.updated(reg.name, regNames)
    case s => freshNames
  }

  def replaceRegRefs(freshNames: FreshNames)(expr: Expression): Expression = expr match {
    case wr @ WRef(name, _, RegKind, FEMALE) => wr.copy(name = freshNames(name).head)
    // 2nd-to-last slot feeds logic; last slot holds a shadow value
    case wr @ WRef(name, _, RegKind, MALE) => wr.copy(name = freshNames(name).init.last)
    case e => e.map(replaceRegRefs(freshNames))
  }

  def transformDepth(depth: BigInt, n: BigInt): BigInt = {
    require(n.bitCount == 1) // pow2 threads for now
    depth * n
  }

  def transformAddr(counter: DefRegister, expr: Expression): Expression = {
    DoPrim(PrimOps.Cat, Seq(expr, WRef(counter)), Nil, UnknownType)
  }

  def updateReg(reg: DefRegister, slotName: String): DefRegister = {
    // Keep self-resets as self-resets
    val newInit = reg.init match {
      case selfRef @ WRef(reg.name, _, _, _) => selfRef.copy(name = slotName)
      case e => e
    }
    // All slot registers are free-running
    reg.copy(name = slotName, init = newInit, clock = WRef(WrapTop.hostClockName))
  }

  def multiThread(freshNames: FreshNames, edgeStatus: collection.Map[WrappedExpression, SignalInfo], n: BigInt, counter: DefRegister)(stmt: Statement): Statement = {
    val multiThreaded = stmt match {
      case mem: DefMemory => mem.copy(depth = transformDepth(mem.depth, n))
      case reg: DefRegister =>
        val newRegs: Seq[DefRegister] = freshNames(reg.name).map(alias => updateReg(reg, alias))
        // Muxing happens between first two stages
        val useNew = edgeStatus(WrappedExpression(reg.clock)).ref
        val gatedUpdate = Connect(FAME5Info, WRef(newRegs.tail.head), Mux(useNew, WRef(newRegs.head), WRef(newRegs.last), UnknownType))
        // Other stages are straight connections
        val directPairs = newRegs.tail zip newRegs.tail.tail
        val directConns = directPairs.map { case (a, b) => Connect(FAME5Info, WRef(b), WRef(a)) }
        Block(newRegs ++: gatedUpdate +: directConns)
      case conn @ Connect(info, lhs, rhs) if kind(lhs) == MemKind =>
        lhs match {
          case WSubField(p: WSubField, "addr", _, _) =>
            Connect(info, lhs, transformAddr(counter, rhs))
          case WSubField(p: WSubField, _, _, _) => conn
          case _ => throw new NotImplementedError("Cannot multithread ${stmt}. Try lowering first.")
        }
      case s => s.map(multiThread(freshNames, edgeStatus, n, counter))
    }
    multiThreaded.map(replaceRegRefs(freshNames))
  }

  def findClocks(clocks: mutable.Set[WrappedExpression])(stmt: Statement): Unit = {
    def findClocksExpr(expr: Expression): Unit = {
      if (expr.tpe == ClockType && Utils.gender(expr) == MALE) {
        clocks += WrappedExpression(expr)
      }
      expr.foreach(findClocksExpr)
    }

    stmt.foreach(findClocksExpr)
    stmt.foreach(findClocks(clocks))
  }

  def apply(m: Module, n: BigInt): Module = {
    val ns = Namespace(m)
    val clockPorts = m.ports.filter(_.tpe == ClockType)
    val hostClock = WRef(WrapTop.hostClockName)
    val hostReset = WRef(WrapTop.hostResetName)
    require(clockPorts.length == 1) // TODO: check for blackboxes
    require(clockPorts.head.name == hostClock.name)

    val clocks = new mutable.LinkedHashSet[WrappedExpression]
    m.body.foreach(findClocks(clocks))

    val edgeStatus = new mutable.LinkedHashMap[WrappedExpression, SignalInfo]
    clocks.foreach {
      case we => we.e1 match {
        case WRef(WrapTop.hostClockName, _, _, _) =>
          // Optimization -- don't generate this gate recovery stuff for host clock
          edgeStatus(we) = SignalInfo(EmptyStmt, EmptyStmt, UIntLiteral(1))
        case e =>
          val edgeCount = DefRegister(FAME5Info, ns.newName("edgeCount"), BoolType, e, hostReset, UIntLiteral(0))
          val updateCount = DefRegister(FAME5Info, ns.newName("updateCount"), BoolType, hostClock, hostReset, UIntLiteral(0))
          val neq = DoPrim(PrimOps.Neq, Seq(WRef(edgeCount), WRef(updateCount)), Nil, BoolType)
          val trackUpdates = Conditionally(FAME5Info, neq, Toggle(updateCount), EmptyStmt)
          edgeStatus(we) = SignalInfo(Block(Seq(edgeCount, updateCount)), Block(Seq(Toggle(edgeCount), trackUpdates)), neq)
      }
    }

    val tidxMax = UIntLiteral(n)
    val tidxType = UIntType(tidxMax.width)
    val tidxRef = WRef(ns.newName("threadIdx"), tidxType, RegKind)
    val tidxDecl = DefRegister(FAME5Info, tidxRef.name, tidxType, hostClock, zero, tidxRef)
    val tidxUpdate = Mux(
      DoPrim(PrimOps.Eq, Seq(tidxRef, tidxMax), Nil, BoolType),
      UIntLiteral(0),
      DoPrim(PrimOps.Add, Seq(tidxRef, one), Nil, BoolType),
      tidxType)
    val tidxConn = Connect(FAME5Info, tidxRef, tidxUpdate)

    val freshNames = renameRegs(HashMap.empty, n, ns, m.body)
    val threaded = multiThread(freshNames, edgeStatus, n, tidxDecl)(m.body)

    val (iDecls, body) = SeparateInstanceDecls(threaded)
    val clockGaters = edgeStatus.toSeq.map { case (k, v) => v }
    m.copy(body = Block(iDecls ++ clockGaters.map(_.decl) ++ Seq(tidxDecl, tidxConn, body) ++ clockGaters.map(_.assigns)))
  }
}
