// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.passes.MemPortUtils._
import firrtl.Utils.{BoolType, kind}

import collection.mutable.ArrayBuffer

// Invalidates WIR _.tpe (types of memories change)
object MuxingMultiThreader {

  type FreshNames = Map[String, Seq[String]]

  val rPortName = "read"
  val wPortName = "write"
  val tIdxName = "threadIdx"

  def rField(mem: DefMemory, field: String): Expression = memPortField(mem, rPortName, field)
  def wField(mem: DefMemory, field: String): Expression = memPortField(mem, wPortName, field)

  def onExprRHS(expr: Expression): Expression = expr match {
    case WRef(name, tpe, RegKind, _) =>
      WSubField(WSubField(WRef(name, tpe, MemKind), rPortName), "data")
    case e => e.map(onExprRHS)
  }

  def regWriteAsMemWrite(info: Info, name: String, tpe: Type, rhs: Expression): Statement = {
    val infos = FAME5Info.info ++ info
    val wPort = WSubField(WRef(name), wPortName)
    val dataConnect = Connect(infos, WSubField(wPort, "data"), rhs)
    val enConnect = Connect(infos, WSubField(wPort, "en"), UIntLiteral(1))
    Block(Seq(dataConnect, enConnect))
  }

  def onStmt(newResets: ArrayBuffer[Statement], nThreads: BigInt, tIdx: Expression)(stmt: Statement): Statement = stmt match {
    case DefRegister(info, name, tpe, clock, reset, init) =>
      val infos = FAME5Info.info ++ info
      val mem = DefMemory(infos, name, tpe, nThreads, 1, 0, Seq(rPortName), Seq(wPortName), Nil)
      val rClockConn = Connect(infos, rField(mem, "clk"), clock)
      val rEnConn = Connect(infos, rField(mem, "en"), UIntLiteral(1))
      val rAddrConn = Connect(infos, rField(mem, "addr"), tIdx)
      val wClockConn = Connect(infos, wField(mem, "clk"), clock)
      val wEnDefault = Connect(infos, wField(mem, "en"), UIntLiteral(0))
      val wMaskConn = Connect(infos, wField(mem, "mask"), UIntLiteral(1))
      val wAddrConn = Connect(infos, wField(mem, "addr"), tIdx)
      if (WrappedExpression(init) != WrappedExpression(WRef(name))) {
        val doReset = regWriteAsMemWrite(info, name, tpe, onExprRHS(init))
        newResets += Conditionally(infos, reset, doReset, EmptyStmt)
      }
      Block(Seq(mem, rClockConn, rEnConn, rAddrConn, wClockConn, wEnDefault, wMaskConn, wAddrConn))
    case Connect(info, WRef(name, tpe, RegKind, _), rhs) =>
      regWriteAsMemWrite(info, name, tpe, onExprRHS(rhs))
    case Connect(_, lhs, _) if (kind(lhs) == RegKind) =>
      throw CustomTransformException(new IllegalArgumentException(s"Cannot handle complex register assignment to ${lhs}"))
    case mem: DefMemory =>
      require(mem.readLatency == 0 || mem.readLatency == 1, "Memories must have combinational or single-cycle reads")
      require(mem.writeLatency == 1, "Memories must have one-cycle write latency")
      Block(ThreadedMem(nThreads, mem),
            Connect(FAME5Info.info, WSubField(WRef(mem.name), WrapTop.hostClockName), WRef(WrapTop.hostClockName)),
            Connect(FAME5Info.info, WSubField(WRef(mem.name), tIdxName), WRef(tIdxName)))
    case s => s.map(onStmt(newResets, nThreads, tIdx)).map(onExprRHS)
  }

  def apply(threadedModuleNames: Map[String, String])(module: Module, n: BigInt): Module = {
    // Simplify all memories first
    val ns = Namespace(module)

    val hostClock = WRef(WrapTop.hostClockName)
    val hostReset = WRef(WrapTop.hostResetName)

    val tIdxMax = UIntLiteral(n-1)
    val tIdxType = UIntType(tIdxMax.width)
    val tIdxRef = WRef(ns.newName("threadIdx"), tIdxType, RegKind)
    val tIdxDecl = DefRegister(FAME5Info.info, tIdxRef.name, tIdxType, hostClock, UIntLiteral(0), tIdxRef)
    val tIdxUpdate = Mux(
      DoPrim(PrimOps.Eq, Seq(tIdxRef, tIdxMax), Nil, BoolType),
      UIntLiteral(0),
      DoPrim(PrimOps.Add, Seq(tIdxRef, UIntLiteral(1)), Nil, BoolType),
      tIdxType)
    val tIdxConn = Connect(FAME5Info.info, tIdxRef, tIdxUpdate)

    // Resets transformed to conditional stores to threading RAMs
    val newResets = new ArrayBuffer[Statement]
    val threaded = onStmt(newResets, n, tIdxRef)(module.body)

    // Uses only threaded instances
    val (iDecls, threadedImpl) = SeparateInstanceDecls(threaded)

    // TODO: earlier in the compiler, every module should get hostClock/hostReset ports hooked up
    val threadedChildren = iDecls.map {
      case i if (threadedModuleNames.contains(i.module)) =>
        AddHostClockAndReset(i.copy(module = threadedModuleNames(i.module)))
      case i => i
    }

    val threadedBody = Block(threadedChildren ++: tIdxDecl +: tIdxConn +: threadedImpl +: newResets.toSeq)
    AddHostClockAndReset(Module(FAME5Info.info ++ module.info, threadedModuleNames(module.name), module.ports, threadedBody))
  }
}
