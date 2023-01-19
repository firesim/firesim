// See LICENSE for license details.

package midas.passes.fame


import firrtl._
import ir._
import Mappers._
import firrtl.Utils.{BoolType, kind}
import firrtl.passes.MemPortUtils
import chisel3.util.log2Ceil


object PatientMemTransformer {
  def apply(mem: DefMemory, finishing: Expression, memClock: WRef, ns: Namespace): Block = {
    val shim = DefWire(NoInfo, mem.name, MemPortUtils.memType(mem))
    val newMem = mem.copy(name = ns.newName(mem.name))
    val defaultConnect = Connect(NoInfo, WRef(shim), WRef(newMem.name, shim.tpe, MemKind))
    val syncReadPorts = (newMem.readers ++ newMem.readwriters).filter(rp => mem.readLatency > 0)
    val preserveReads = syncReadPorts.flatMap {
      case rpName =>
        val addrWidth = IntWidth(log2Ceil(mem.depth) max 1)
        val dummyReset = DefWire(NoInfo, ns.newName(s"${mem.name}_${rpName}_dummyReset"), BoolType)
        val tieOff = Connect(NoInfo, WRef(dummyReset), UIntLiteral(0))
        val addrReg = new DefRegister(NoInfo, ns.newName(s"${mem.name}_${rpName}"),
          UIntType(addrWidth), memClock, WRef(dummyReset), UIntLiteral(0, addrWidth))
        val updateReg = Connect(NoInfo, WRef(addrReg), WSubField(WSubField(WRef(shim), rpName), "addr"))
        val useReg = Connect(NoInfo, MemPortUtils.memPortField(newMem, rpName, "addr"), WRef(addrReg))
        Seq(dummyReset, tieOff, addrReg, Conditionally(NoInfo, finishing, updateReg, useReg))
    }
    val gateWrites = (newMem.writers ++ newMem.readwriters).map {
      case wpName =>
        Conditionally(
          NoInfo,
          Negate(finishing),
          Connect(NoInfo, MemPortUtils.memPortField(newMem, wpName, "en"), UIntLiteral(0, IntWidth(1))),
          EmptyStmt)
      }
    new Block(Seq(shim, newMem, defaultConnect) ++ preserveReads ++ gateWrites)
  }
}

object PatientSSMTransformer {
  def apply(m: Module, analysis: FAMEChannelAnalysis)(implicit triggerName: String): Module = {
    val ns = Namespace(m)
    val clocks = m.ports.filter(_.tpe == ClockType)
    // TODO: turn this back on
    // assert(clocks.length == 1)
    val finishing = new Port(NoInfo, ns.newName(triggerName), Input, BoolType)
    val hostClock = clocks.find(_.name == "clock").getOrElse(clocks.head) // TODO: naming convention for host clock
    def onStmt(stmt: Statement): Statement = stmt.map(onStmt) match {
      case conn @ Connect(info, lhs, _) if (kind(lhs) == RegKind) =>
        Conditionally(info, WRef(finishing), conn, EmptyStmt)
      case s: Stop  => s.copy(en = DoPrim(PrimOps.And, Seq(WRef(finishing), s.en), Seq.empty, BoolType))
      case p: Print => p.copy(en = DoPrim(PrimOps.And, Seq(WRef(finishing), p.en), Seq.empty, BoolType))
      case mem: DefMemory => PatientMemTransformer(mem, WRef(finishing), WRef(hostClock), ns)
      case wi: WDefInstance if analysis.syncNativeModules.contains(analysis.moduleTarget(wi)) =>
        new Block(Seq(wi, Connect(wi.info, WSubField(WRef(wi), triggerName), WRef(finishing))))
      case s => s
    }
    Module(m.info, m.name, m.ports :+ finishing, m.body.map(onStmt))
  }
}
