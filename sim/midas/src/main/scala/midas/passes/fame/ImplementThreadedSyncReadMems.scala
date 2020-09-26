// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import firrtl.ir._
import firrtl.passes.MemPortUtils._

import firrtl.Mappers._

import collection.mutable

/*
 * The multithreaders add custom IR nodes to represent threaded sync-read mems.
 * This pass finds them, replaces them with blackbox instances, and implements the blackboxes.
 */

object ThreadedSyncReadMem {
  def apply(nThreads: BigInt, proto: DefMemory): ThreadedSyncReadMem = {
    ThreadedSyncReadMem(nThreads, proto.info, proto.name, proto.dataType, proto.depth,
      proto.readers, proto.writers, proto.readwriters, proto.readUnderWrite)
  }
}

case class ThreadedSyncReadMem(
  nThreads: BigInt,
  info: Info,
  name: String,
  dataType: Type,
  depth: BigInt,
  readers: Seq[String],
  writers: Seq[String],
  readwriters: Seq[String],
  readUnderWrite: ReadUnderWrite.Value) extends Statement with IsDeclaration {

  def threadImpl(desiredName: String): DefMemory = {
    DefMemory(info, desiredName, dataType, depth, 1, 1, readers, writers, readwriters, readUnderWrite)
  }

  def serialize = ???
  def mapStmt(f: Statement => Statement): Statement = this
  def mapExpr(f: Expression => Expression): Statement = this
  def mapType(f: Type => Type): Statement = this.copy(dataType = f(dataType))
  def mapString(f: String => String): Statement = this.copy(name = f(name))
  def mapInfo(f: Info => Info): Statement = this.copy(info = f(info))
  def foreachExpr(f: Expression => Unit): Unit = ()
  def foreachStmt(f: Statement => Unit): Unit = ()
  def foreachType(f: Type => Unit): Unit = f(dataType)
  def foreachString(f: String => Unit): Unit = f(name)
  def foreachInfo(f: Info => Unit): Unit = f(info)
}

object ImplementThreadedSyncReadMems extends Transform {
  def inputForm = HighForm
  def outputForm = HighForm

  private def implement(tMem: ThreadedSyncReadMem, moduleName: String): Module = {
    val info = FAME5Info.info
    val tIdxPort = Port(info, "tidx", Input, UIntLiteral(tMem.nThreads).tpe)
    val ports = tIdxPort +: memType(tMem.threadImpl("")).fields.map {
      case Field(name, Flip, tpe) => Port(info, name, Input, tpe)
    }
    val ns = Namespace(ports.map(p => p.name))

    val dataVecType = VectorType(tMem.dataType, tMem.nThreads.toInt)
    val rDataVecNames = tMem.readers.map(r => r -> ns.newName(s"${r}_datas")).toMap
    val rwDataVecNames = tMem.readwriters.map(rw => rw -> ns.newName(s"${rw}_rdatas")).toMap
 
    val mems = (0 until tMem.nThreads.toInt).map(i => tMem.threadImpl(ns.newName(s"mem_${i}")))
    val rDataVecs = rDataVecNames.map { case (k, v) => DefWire(info, v, dataVecType) }
    val rwDataVecs = rwDataVecNames.map { case (k, v) => DefWire(info, v, dataVecType) }
    val defaultConns = mems.zipWithIndex.flatMap {
      case (mem, i) =>
        (mem.readers ++ mem.writers ++ mem.readwriters).flatMap { pName =>
          val topP = WRef(pName)
          val memP = WSubField(WRef(mem), pName)
          val maskedEn = And(WSubField(topP, "en"), Eq(WRef(tIdxPort), UIntLiteral(i)))
          val rDataElementConn = rDataVecNames.get(pName).map(rdVec => Connect(info, WSubIndex(WRef(rdVec), i, tMem.dataType, SinkFlow), WSubField(memP, "data")))
          val rwDataElementConn = rwDataVecNames.get(pName).map(rwdVec => Connect(info, WSubIndex(WRef(rwdVec), i, tMem.dataType, SinkFlow), WSubField(memP, "rdata")))
          Seq(Connect(info, memP, topP), Connect(info, WSubField(memP, "en"), maskedEn)) ++ rDataElementConn ++ rwDataElementConn
        }
    }
    val rDataSelects = tMem.readers.map(r => Connect(info, WSubField(WRef(r), "data"), WSubAccess(WRef(rDataVecNames(r)), WRef(tIdxPort), tMem.dataType, SourceFlow)))
    val rwDataSelects = tMem.readwriters.map(rw => Connect(info, WSubField(WRef(rw), "rdata"), WSubAccess(WRef(rwDataVecNames(rw)), WRef(tIdxPort), tMem.dataType, SourceFlow)))

    Module(info, moduleName, ports, Block(mems ++ rDataVecs ++ rwDataVecs ++ defaultConns ++ rDataSelects ++ rwDataSelects))
  }

  private def onStmt(ns: Namespace, implementations: mutable.Map[ThreadedSyncReadMem, Module])(stmt: Statement): Statement = {
    stmt match {
      case tMem: ThreadedSyncReadMem =>
        val impl = implementations.getOrElseUpdate(tMem.copy(name = ""), implement(tMem, ns.newName("ThreadedMem")))
        WDefInstance(tMem.name, impl.name)
      case s => s.map(onStmt(ns, implementations)(_))
    }
  }

  override def execute(state: CircuitState): CircuitState = {
    val moduleNS = Namespace(state.circuit)
    val tMemImplementations = new mutable.LinkedHashMap[ThreadedSyncReadMem, Module]
    val modulesX = state.circuit.modules.map {
      case m: Module => m.copy(body = onStmt(moduleNS, tMemImplementations)(m.body))
      case m => m
    }
    val tMemMods = tMemImplementations.map { case (k, v) => v }
    state.copy(circuit = state.circuit.copy(modules = modulesX ++ tMemMods))
  }
}
