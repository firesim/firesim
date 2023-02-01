// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.passes.memlib
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

  def apply(stmt: Statement): (Seq[WDefInstance], Statement) = {
    val insts = new mutable.ArrayBuffer[WDefInstance]
    val otherStmts = onStmt(insts)(stmt)
    (insts.toSeq, otherStmts)
  }
}

object AddHostClockAndReset {
  val hostClock = Port(FAME5Info.info, WrapTop.hostClockName, Input, ClockType)
  val hostReset = Port(FAME5Info.info, WrapTop.hostResetName, Input, BoolType)

  def apply(m: Module): Module = {
    m.copy(ports = m.ports ++ Seq(hostClock, hostReset).filterNot(p => m.ports.map(_.name).contains(p.name)))
  }

  def apply(wi: WDefInstance): Statement = {
    // Adds connections to instance hostClock/hostReset ports
    val hcConn = Connect(FAME5Info.info, WSubField(WRef(wi), WrapTop.hostClockName), WRef(WrapTop.hostClockName))
    val hrConn = Connect(FAME5Info.info, WSubField(WRef(wi), WrapTop.hostResetName), WRef(WrapTop.hostResetName))
    Block(Seq(wi, hcConn, hrConn))
  }
}

object Toggle {
  def apply(r: DefRegister): Statement = {
    Connect(FAME5Info.info, WRef(r), DoPrim(PrimOps.Not, Seq(WRef(r)), Nil, r.tpe))
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
      val regNames = (0 to n.intValue).map(i => ns.newName(s"${reg.name}_slot_${i}"))
      freshNames.updated(reg.name, regNames)
    case s => freshNames
  }

  def replaceRegRefsLHS(freshNames: FreshNames)(expr: Expression): Expression = expr match {
    case wr @ WRef(name, _, RegKind, _) => wr.copy(name = freshNames(name).head)
    case e => e.map(replaceRegRefsLHS(freshNames))
  }

  def replaceRegRefsRHS(freshNames: FreshNames)(expr: Expression): Expression = expr match {
    // 2nd-to-last slot feeds logic; last slot holds a shadow value
    case wr @ WRef(name, _, RegKind, _) => wr.copy(name = freshNames(name).init.last)
    case e => e.map(replaceRegRefsRHS(freshNames))
  }

  def replaceRegRefs(freshNames: FreshNames)(expr: Expression): Expression = expr match {
    case wr @ WRef(name, _, RegKind, SinkFlow) => wr.copy(name = freshNames(name).head)
    // 2nd-to-last slot feeds logic; last slot holds a shadow value
    case wr @ WRef(name, _, RegKind, SourceFlow) => wr.copy(name = freshNames(name).init.last)
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
      case wr: WRef if (wr.name == reg.name) => wr.copy(name = slotName)
      case e => e
    }
    // All slot registers are free-running
    reg.copy(name = slotName, init = newInit, clock = WRef(WrapTop.hostClockName))
  }

  def multiThread(freshNames: FreshNames, edgeStatus: collection.Map[WrappedExpression, SignalInfo], n: BigInt, counter: DefRegister)(stmt: Statement): Statement = {
    stmt match {
      case mem: DefMemory =>
        assert(mem.readLatency == 0 && mem.writeLatency == 1, "Memories must be transformed with VerilogMemDelays before multithreading")
        mem.copy(depth = transformDepth(mem.depth, n), readLatency = mem.readLatency * n.intValue)
      case reg: DefRegister =>
        val newRegs: Seq[DefRegister] = freshNames(reg.name).map(alias => updateReg(reg, alias))
        // Muxing happens between first two stages
        val useNew = edgeStatus(WrappedExpression(reg.clock)).ref
        val gatedUpdate = Connect(FAME5Info.info, WRef(newRegs.tail.head), Mux(useNew, WRef(newRegs.head), WRef(newRegs.last), UnknownType))
        // Other stages are straight connections
        val directPairs = newRegs.tail zip newRegs.tail.tail
        val directConns = directPairs.map { case (a, b) => Connect(FAME5Info.info, WRef(b), WRef(a)) }
        Block(newRegs ++: gatedUpdate +: directConns)
      case Connect(info, lhs @ WSubField(p: WSubField, "addr", _, _), rhs) if kind(lhs) == MemKind =>
        Connect(info, replaceRegRefsLHS(freshNames)(lhs), transformAddr(counter, replaceRegRefsRHS(freshNames)(rhs)))
      case Connect(info, lhs, rhs) =>
        // TODO: LHS vs RHS is kind of a hack
        // We need a new method to swap register refs on the LHS because VerilogMemDelays puts in un-flowed register refs
        Connect(info, replaceRegRefsLHS(freshNames)(lhs), replaceRegRefsRHS(freshNames)(rhs))
      case s => s.map(multiThread(freshNames, edgeStatus, n, counter)).map(replaceRegRefsRHS(freshNames))
    }
  }

  def findClocks(clocks: mutable.Set[WrappedExpression])(stmt: Statement): Unit = {
    def findClocksExpr(expr: Expression): Unit = {
      if (expr.tpe == ClockType && Utils.flow(expr) == SourceFlow) {
        clocks += WrappedExpression(expr)
      }
      expr.foreach(findClocksExpr)
    }

    stmt.foreach(findClocksExpr)
    stmt.foreach(findClocks(clocks))
  }

  def apply(threadedModuleNames: Map[String, String])(module: Module, n: BigInt): Module = {
    // TODO: this is ugly and uses copied code instead of bumping FIRRTL
    // Simplify all memories first
    val loweredMod = (new memlib.MemDelayAndReadwriteTransformer(module)).transformed.asInstanceOf[Module]

    val ns = Namespace(loweredMod)
    val hostClock = WRef(WrapTop.hostClockName)
    val hostReset = WRef(WrapTop.hostResetName)

    val clocks = new mutable.LinkedHashSet[WrappedExpression]
    loweredMod.body.foreach(findClocks(clocks))

    val edgeStatus = new mutable.LinkedHashMap[WrappedExpression, SignalInfo]
    clocks.foreach {
      case we => we.e1 match {
        case WRef(WrapTop.hostClockName, _, _, _) =>
          // Optimization -- don't generate this gate recovery stuff for host clock
          edgeStatus(we) = SignalInfo(EmptyStmt, EmptyStmt, UIntLiteral(1))
        case e =>
          val edgeCount = DefRegister(FAME5Info.info, ns.newName("edgeCount"), BoolType, e, hostReset, UIntLiteral(0))
          val updateCount = DefRegister(FAME5Info.info, ns.newName("updateCount"), BoolType, hostClock, hostReset, UIntLiteral(0))
          val neq = DoPrim(PrimOps.Neq, Seq(WRef(edgeCount), WRef(updateCount)), Nil, BoolType)
          val trackUpdates = Conditionally(FAME5Info.info, neq, Toggle(updateCount), EmptyStmt)
          edgeStatus(we) = SignalInfo(Block(Seq(edgeCount, updateCount)), Block(Seq(Toggle(edgeCount), trackUpdates)), neq)
      }
    }

    val tidxMax = UIntLiteral(n-1)
    val tidxType = UIntType(tidxMax.width)
    val tidxRef = WRef(ns.newName("threadIdx"), tidxType, RegKind)
    val tidxDecl = DefRegister(FAME5Info.info, tidxRef.name, tidxType, hostClock, zero, tidxRef)
    val tidxUpdate = Mux(
      DoPrim(PrimOps.Eq, Seq(tidxRef, tidxMax), Nil, BoolType),
      UIntLiteral(0),
      DoPrim(PrimOps.Add, Seq(tidxRef, one), Nil, BoolType),
      tidxType)
    val tidxConn = Connect(FAME5Info.info, tidxRef, tidxUpdate)

    val freshNames = renameRegs(HashMap.empty, n, ns, loweredMod.body)
    val threaded = multiThread(freshNames, edgeStatus, n, tidxDecl)(loweredMod.body)

    // Uses only threaded instances
    val (iDecls, body) = SeparateInstanceDecls(threaded)

    val threadedChildren = iDecls.map {
      case i if (threadedModuleNames.contains(i.module)) =>
        AddHostClockAndReset(i.copy(module = threadedModuleNames(i.module)))
      case i => i
    }

    val clockGaters = edgeStatus.toSeq.map { case (k, v) => v }
    val threadedBody = Block(threadedChildren ++ clockGaters.map(_.decl) ++ Seq(tidxDecl, tidxConn, body) ++ clockGaters.map(_.assigns))

    val hostPorts = Seq(Port(FAME5Info.info, hostClock.name, Input, ClockType), Port(FAME5Info.info, hostReset.name, Input, BoolType))
    val newPorts = module.ports ++ hostPorts.filterNot(p => module.ports.map(_.name).contains(p.name))
    Module(FAME5Info.info ++ module.info, threadedModuleNames(module.name), newPorts, threadedBody)
  }
}
