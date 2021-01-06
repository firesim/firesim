// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import firrtl.ir._
import firrtl.Utils.BoolType
import firrtl.passes.MemPortUtils._

import firrtl.Mappers._

import collection.mutable

/*
 * The multithreaders add custom IR nodes to represent threaded memories.
 * This pass finds them, replaces them with blackbox instances, and implements the blackboxes.
 */

case class ThreadedMem(nThreads: BigInt, proto: DefMemory) extends Statement with IsDeclaration {
  require(proto.readLatency == 0 || proto.readLatency == 1)

  val name = proto.name
  val info = proto.info
  def serialize = ???
  def mapStmt(f: Statement => Statement): Statement = this
  def mapExpr(f: Expression => Expression): Statement = this
  def mapType(f: Type => Type): Statement = this.copy(proto = proto.copy(dataType = f(proto.dataType)))
  def mapString(f: String => String): Statement = this.copy(proto = proto.copy(name = f(proto.name)))
  def mapInfo(f: Info => Info): Statement = this.copy(proto = proto.copy(info = f(proto.info)))
  def foreachExpr(f: Expression => Unit): Unit = ()
  def foreachStmt(f: Statement => Unit): Unit = ()
  def foreachType(f: Type => Unit): Unit = proto.foreachType(f)
  def foreachString(f: String => Unit): Unit = proto.foreachString(f)
  def foreachInfo(f: Info => Unit): Unit = proto.foreachInfo(f)
}

object ImplementThreadedMems {
  private def wsf(e: Expression, f: String): WSubField = WSubField(e, f)
  private def wssf(e: Expression, f0: String, f1: String): WSubField = WSubField(wsf(e, f0), f1)
  private def prim(op: PrimOp, args: Seq[Expression]) = DoPrim(op, args, Nil, UnknownType)

  private def implement(tMem: ThreadedMem, moduleName: String): Module = {
    val info = FAME5Info.info
    val tIdxMax = UIntLiteral(tMem.nThreads-1)

    // If there are at least 4 threads, we can extend the latency of read data propagation
    val bramToBufferPipeDepth = if (tMem.nThreads < 4) 0 else 1
    val bufferReadLatency = if (tMem.nThreads < 4) 0 else 1

    val hostClockPort = Port(info, WrapTop.hostClockName, Input, ClockType)
    val tIdxPort = Port(info, MuxingMultiThreader.tIdxName, Input, tIdxMax.tpe)

    val accessors = tMem.proto.readers ++ tMem.proto.writers ++ tMem.proto.readwriters
    val ports = hostClockPort +: tIdxPort +: memType(tMem.proto).fields.map {
      case Field(name, Flip, tpe) => Port(info, name, Input, tpe)
    }

    val ns = Namespace(ports.map(p => p.name))

    def hostRegNext(name: String, expr: Expression): (Block, WRef) = {
      val reg = DefRegister(info, name, expr.tpe, WRef(hostClockPort), UIntLiteral(0), WRef(name))
      val conn = Connect(info, WRef(reg), expr)
      (Block(reg, conn), WRef(reg))
    }

    def pipeline(depth: Int, expr: WRef): (Block, WRef) = {
      (1 to depth).foldLeft[(Block, WRef)]((Block(Nil), expr)) {
        case ((prevBlock, prev), idx) =>
          val (currentBlock, current) = hostRegNext(ns.newName(s"${expr.name}_p${idx}"), prev)
          (Block(prevBlock.stmts ++: currentBlock.stmts), current)
      }
    }

    // Useful expressions: tidx is carried in the bottom of input addr (part of interface definition)
    val targetClock = wsf(WRef(accessors.head), "clk")
    val targetClockCounterName = ns.newName("edgeCount")

    val mem = tMem.proto.copy(depth = tMem.proto.depth * tMem.nThreads)
    val addrTranslations = accessors.flatMap {
      p =>
        if (tMem.nThreads.bitCount == 1) {
          Seq(Connect(info, wssf(WRef(mem), p, "addr"), prim(PrimOps.Cat, Seq(wsf(WRef(p), "addr"), WRef(tIdxPort)))))
        } else {
          val maxBase = UIntLiteral((tMem.nThreads-1)*tMem.proto.depth)
          val baseName = ns.newName("base_addr_counter")
          val baseRef = WRef(baseName)
          val baseDecl = DefRegister(info, baseName, maxBase.tpe, WRef(hostClockPort), UIntLiteral(0), baseRef)
          val baseUpdate = Connect(
            info,
            baseRef,
            Mux(prim(PrimOps.Geq, Seq(baseRef, maxBase)), UIntLiteral(0), prim(PrimOps.Add, Seq(baseRef, UIntLiteral(tMem.proto.depth))))
          )
          Seq(baseDecl, baseUpdate, Connect(info, wssf(WRef(mem), p, "addr"), prim(PrimOps.Add, Seq(baseRef, wsf(WRef(p), "addr")))))
        }
    }

    if (tMem.proto.readLatency == 0) {
      val defaultConns = accessors.map(p => Connect(info, wsf(WRef(mem), p), WRef(p)))
      val body = Block(Seq(mem) ++ defaultConns ++ addrTranslations)
      Module(info, moduleName, ports, body)
    } else {
      // Name the memories used to store read data
      val rdMemNames = tMem.proto.readers.map(r => r -> ns.newName(s"${r}_datas")).toMap
      val rwdMemNames = tMem.proto.readwriters.map(rw => rw -> ns.newName(s"${rw}_rdatas")).toMap

      // Now all the statements
      val targetClockCounter = DefRegister(info, targetClockCounterName, BoolType, targetClock, UIntLiteral(0), WRef(targetClockCounterName))
      val counterUpdate = Connect(info, WRef(targetClockCounter), Negate(WRef(targetClockCounter)))

      val (counterTracker, counterTrackerRef) = hostRegNext(ns.newName("edgeCountTracker"), WRef(targetClockCounter))
      val edgeStatus = DefNode(info, ns.newName("edgeStatus"), Xor(counterTrackerRef, WRef(targetClockCounter)))


      // The buffer addresses are arbitrary. Intuitively, with no pipelineing, we would write to tIdxLast and read from tIdx.
      // Here, they are shifted back by (bufferReadLatency + 1) so that we always have pipelined addresses.
      // More importantly, we never have to *increment* tIdx when adding buffer read latency.
      val (tIdxLast, tIdxLastRef) = pipeline(1, WRef(tIdxPort))
      val (tIdxPipe, tIdxPipedRef) = pipeline(bramToBufferPipeDepth + bufferReadLatency + 1, tIdxLastRef)
      val (edgeStatusPipe, edgeStatusPipedRef) = pipeline(bramToBufferPipeDepth, WRef(edgeStatus))

      val rdMems = rdMemNames.map { case (k, v) => DefMemory(info, v, mem.dataType, tMem.nThreads, 1, bufferReadLatency, Seq("r"), Seq("w"), Nil) }
      val rwdMems = rwdMemNames.map { case (k, v) => DefMemory(info, v, mem.dataType, tMem.nThreads, 1, bufferReadLatency, Seq("r"), Seq("w"), Nil) }

      val defaultConns = accessors.map(p => Connect(info, wsf(WRef(mem), p), WRef(p)))

      val dataOutLogic = (rdMemNames ++ rwdMemNames).flatMap {
        case (topPName, doutMemName) =>
          val doutName = if (rdMemNames.contains(topPName)) "data" else "rdata"
          val doutMemRef = WRef(doutMemName)
          val tmpNodeName = ns.newName(s"${topPName}_${doutName}_node")

          val doutNode = DefNode(info, tmpNodeName, wssf(WRef(mem), topPName, doutName))
          val (doutPipe, doutPipedRef) = pipeline(bramToBufferPipeDepth, WRef(tmpNodeName, tMem.proto.dataType, NodeKind, SourceFlow))
          Seq(doutNode,
              doutPipe,
              Connect(info, wssf(doutMemRef, "r", "clk"), WRef(hostClockPort)),
              Connect(info, wssf(doutMemRef, "r", "addr"), tIdxLastRef),
              Connect(info, wssf(doutMemRef, "r", "en"), UIntLiteral(1)),
              Connect(info, wsf(WRef(topPName), doutName), wssf(doutMemRef, "r", "data")),
              Connect(info, wssf(doutMemRef, "w", "clk"), WRef(hostClockPort)),
              Connect(info, wssf(doutMemRef, "w", "addr"), tIdxPipedRef),
              Connect(info, wssf(doutMemRef, "w", "en"), edgeStatusPipedRef),
              Connect(info, wssf(doutMemRef, "w", "mask"), UIntLiteral(1)),
              Connect(info, wssf(doutMemRef, "w", "data"), doutPipedRef))
      }

      val ctrlStmts = Seq(targetClockCounter, counterUpdate, counterTracker, edgeStatus, tIdxLast, tIdxPipe, edgeStatusPipe)
      val body = Block(Seq(mem) ++ ctrlStmts ++ rdMems ++ rwdMems ++ defaultConns ++ addrTranslations ++ dataOutLogic)

      Module(info, moduleName, ports, body)
    }
  }

  private def onStmt(ns: Namespace, implementations: mutable.Map[ThreadedMem, Module])(stmt: Statement): Statement = {
    stmt match {
      case tMem: ThreadedMem =>
        val anon = tMem.copy(proto = tMem.proto.copy(name = ""))
        val impl = implementations.getOrElseUpdate(anon, implement(tMem, ns.newName("ThreadedMem")))
        WDefInstance(tMem.proto.name, impl.name)
      case s => s.map(onStmt(ns, implementations)(_))
    }
  }

  def apply(circuit: Circuit): Circuit = {
    val moduleNS = Namespace(circuit)
    val tMemImplementations = new mutable.LinkedHashMap[ThreadedMem, Module]
    val modulesX = circuit.modules.map {
      case m: Module => m.copy(body = onStmt(moduleNS, tMemImplementations)(m.body))
      case m => m
    }
    val tMemMods = tMemImplementations.map { case (k, v) => v }
    circuit.copy(modules = modulesX ++ tMemMods)
  }
}
