// See LICENSE for license details.

package midas.passes.fame

import java.io.{PrintWriter, File}

import firrtl._
import ir._
import Mappers._
import firrtl.Utils.{BoolType, kind, ceilLog2, one}
import firrtl.passes.MemPortUtils
import annotations._
import scala.collection.mutable
import mutable.{LinkedHashSet, LinkedHashMap}

import midas.passes._

/**************
 PRECONDITIONS:
 **************
 1.) Ports do not have aggregate types (easy to support if necessary)
 2.) There are no collisions among input/output channel names
 */

trait FAME1Channel {
  def name: String
  def direction: Direction
  def ports: Seq[Port]
  def tpe: Type = FAMEChannelAnalysis.getHostDecoupledChannelType(name, ports)
  def portName: String
  def asPort: Port = Port(NoInfo, portName, direction, tpe)
  def isReady: Expression = WSubField(WRef(asPort), "ready", BoolType)
  def isValid: Expression = WSubField(WRef(asPort), "valid", BoolType)
  def isFiring: Expression = Reduce.and(Seq(isReady, isValid))
  def replacePortRef(wr: WRef): WSubField = {
    if (ports.size > 1) {
      WSubField(WSubField(WRef(asPort), "bits"), FAMEChannelAnalysis.removeCommonPrefix(wr.name, name)._1)
    } else {
      WSubField(WRef(asPort), "bits")
    }
  }
}

case class FAME1InputChannel(val name: String, val ports: Seq[Port]) extends FAME1Channel {
  val direction = Input
  val portName = s"${name}_sink"
  def genTokenLogic(finishing: WRef): Seq[Statement] = {
    Seq(Connect(NoInfo, isReady, finishing))
  }
}

case class FAME1OutputChannel(val name: String, val ports: Seq[Port], val firedReg: DefRegister) extends FAME1Channel {
  val direction = Output
  val portName = s"${name}_source"
  val isFired = WRef(firedReg)
  val isFiredOrFiring = Reduce.or(Seq(isFired, isFiring))
  def genTokenLogic(finishing: WRef, ccDeps: Iterable[FAME1InputChannel]): Seq[Statement] = {
    val regUpdate = Connect(
      NoInfo,
      isFired,
      Mux(finishing,
        UIntLiteral(0, IntWidth(1)),
        isFiredOrFiring,
        BoolType))
    val setValid = Connect(
      NoInfo,
      isValid,
      Reduce.and(ccDeps.map(_.isValid) ++ Seq(Negate(isFired))))
    Seq(regUpdate, setValid)
  }
}

object ChannelCCDependencyGraph {
  def apply(m: Module): LinkedHashMap[FAME1OutputChannel, LinkedHashSet[FAME1InputChannel]] = {
    new LinkedHashMap[FAME1OutputChannel, LinkedHashSet[FAME1InputChannel]]
  }
}

object PatientMemTransformer {
  def apply(mem: DefMemory, finishing: Expression, memClock: WRef, ns: Namespace): Block = {
    val shim = DefWire(NoInfo, mem.name, MemPortUtils.memType(mem))
    val newMem = mem.copy(name = ns.newName(mem.name))
    val defaultConnect = Connect(NoInfo, WRef(shim), WRef(newMem.name, shim.tpe, MemKind))
    val syncReadPorts = (newMem.readers ++ newMem.readwriters).filter(rp => mem.readLatency > 0)
    val preserveReads = syncReadPorts.flatMap {
      case rpName =>
        val addrWidth = IntWidth(ceilLog2(mem.depth) max 1)
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

object FAMEModuleTransformer {
  def apply(m: Module, analysis: FAMEChannelAnalysis)(implicit triggerName: String): Module = {
    // Step 0: Special signals & bookkeeping
    implicit val ns = Namespace(m)
    val clocks = m.ports.filter(_.tpe == ClockType)
    // TODO: turn this back to == 1
    assert(clocks.length >= 1)
    val hostClock = clocks.find(_.name == "clock").getOrElse(clocks.head) // TODO: naming convention for host clock
    val hostReset = HostReset.makePort(ns)
    def createHostReg(name: String = "host", width: Width = IntWidth(1)): DefRegister = {
      new DefRegister(NoInfo, ns.newName(name), UIntType(width), WRef(hostClock), WRef(hostReset), UIntLiteral(0, width))
    }
    val finishing = DefWire(NoInfo, ns.newName(triggerName), BoolType)

    val gateTargetClock = analysis.containsSyncBlackboxes(m)
    val targetClock = if (gateTargetClock) {
      val buf = InstanceInfo(DefineAbstractClockGate.blackbox).connect("I", WRef(hostClock)).connect("CE", WRef(finishing))
      SignalInfo(buf.decl, buf.assigns, WSubField(buf.ref, "O", ClockType, SourceFlow))
    } else {
      PassThru(WRef(hostClock), "target_clock")
    }

    // Step 1: Build channels
    val mTarget = ModuleTarget(analysis.circuit.main, m.name)
    val inChannels = (analysis.modelInputChannelPortMap(mTarget)).map({
      case(cName, ports) => new FAME1InputChannel(cName, ports)
    })
    val inChannelMap = new LinkedHashMap[String, FAME1InputChannel] ++
      (inChannels.flatMap(c => c.ports.map(p => (p.name, c))))

    val outChannels = analysis.modelOutputChannelPortMap(mTarget).map({
      case(cName, ports) =>
        val firedReg = createHostReg(name = ns.newName(s"${cName}_fired"))
        new FAME1OutputChannel(cName, ports, firedReg)
    })
    val outChannelMap = new LinkedHashMap[String, FAME1OutputChannel] ++
      (outChannels.flatMap(c => c.ports.map(p => (p.name, c))))
    val decls = Seq(finishing) ++ outChannels.map(_.firedReg)


    // Step 2: Find combinational dependencies
    val ccChecker = new firrtl.transforms.CheckCombLoops
    val portDeps = analysis.connectivity(m.name)
    val ccDeps = new LinkedHashMap[FAME1OutputChannel, LinkedHashSet[FAME1InputChannel]]
    portDeps.getEdgeMap.collect({ case (o, iSet) if outChannelMap.contains(o) =>
      // Only add input channels, since output might depend on output RHS ref
      ccDeps.getOrElseUpdate(outChannelMap(o), new LinkedHashSet[FAME1InputChannel]) ++= iSet.flatMap(inChannelMap.get(_))
    })

    // Step 3: transform ports
    val transformedPorts = clocks ++ Seq(hostReset) ++ inChannels.map(_.asPort) ++ outChannels.map(_.asPort)

    // Step 4: Replace refs and gate state updates
    def onExpr(expr: Expression): Expression = expr.map(onExpr) match {
    case iWR @ WRef(name, tpe, PortKind, SourceFlow) if tpe != ClockType =>
        // Generally SourceFlow references to ports will be input channels, but RTL may use
        // an assignment to an output port as something akin to a wire, so check output ports too.
        inChannelMap.getOrElse(name, outChannelMap(name)).replacePortRef(iWR)
      case oWR @ WRef(name, tpe, PortKind, SinkFlow) if tpe != ClockType =>
        outChannelMap(name).replacePortRef(oWR)
      case wr: WRef if wr.name == hostClock.name =>
        // Replace host clock references with target clock references
        targetClock.ref
      case e => e
    }

   /*
    * A target state trigger is only needed if clocks are not gated.  When using true clock gating,
    * the transform still programmatically adds an extra enable signal to the state updates, so we
    * pass in a constant one as the value of this enable signal.  This spurious condition gets
    * optimized away during ConstantPropagation. This homogeneity is helpful since a transformed
    * module might be instantiated within multiple top-level simulation models, some of which may
    * rely true clock gating (if their corresponding target modules contain blackboxes) and some of
    * which may not.
    */
    val targetStateTrigger = if (gateTargetClock) one else WRef(finishing)

    def onStmt(stmt: Statement): Statement = stmt.map(onStmt).map(onExpr) match {
      case conn @ Connect(info, lhs, _) if (kind(lhs) == RegKind) =>
        Conditionally(info, targetStateTrigger, conn, EmptyStmt)
      case mem: DefMemory => PatientMemTransformer(mem, targetStateTrigger, WRef(hostClock), ns)
      case wi: WDefInstance if analysis.syncNativeModules.contains(analysis.moduleTarget(wi)) =>
        new Block(Seq(wi, Connect(wi.info, WSubField(WRef(wi), triggerName), targetStateTrigger)))
      case s: Stop  => s.copy(en = DoPrim(PrimOps.And, Seq(targetStateTrigger, s.en), Seq.empty, BoolType))
      case p: Print => p.copy(en = DoPrim(PrimOps.And, Seq(targetStateTrigger, p.en), Seq.empty, BoolType))
      case s => s
    }

    val transformedStmts = Seq(m.body.map(onStmt))

    // Step 5: Add firing rules for output channels, trigger end of cycle
    val ruleStmts = new mutable.ArrayBuffer[Statement]
    ruleStmts ++= outChannels.flatMap(o => o.genTokenLogic(WRef(finishing), ccDeps(o)))
    ruleStmts ++= inChannels.flatMap(i => i.genTokenLogic(WRef(finishing)))
    ruleStmts += Connect(NoInfo, WRef(finishing),
      Reduce.and(outChannels.map(_.isFiredOrFiring) ++ inChannels.map(_.isValid)))

    // Statements have to be conservatively ordered to satisfy declaration order
    val allStmts = targetClock.decl +: (decls ++ transformedStmts ++ ruleStmts) :+ targetClock.assigns
    Module(m.info, m.name, transformedPorts, new Block(allStmts))
  }
}

class FAMETransform extends Transform {
  def inputForm = LowForm
  def outputForm = HighForm

  def updateNonChannelConnects(analysis: FAMEChannelAnalysis)(stmt: Statement): Statement = stmt.map(updateNonChannelConnects(analysis)) match {
    case wi: WDefInstance if (analysis.transformedModules.contains(analysis.moduleTarget(wi))) =>
      val resetConn = Connect(NoInfo, WSubField(WRef(wi), "hostReset"), WRef(analysis.hostReset.ref, BoolType))
      Block(Seq(wi, resetConn))
    case Connect(_, WRef(name, _, _, _), _) if (analysis.staleTopPorts.contains(analysis.topTarget.ref(name))) => EmptyStmt
    case Connect(_, _, WRef(name, _, _, _)) if (analysis.staleTopPorts.contains(analysis.topTarget.ref(name))) => EmptyStmt
    case s => s
  }

  def hostDecouplingRenames(analysis: FAMEChannelAnalysis): RenameMap = {
    // Handle renames at the top-level to new channelized names
    val renames = RenameMap()
    val sinkRenames = analysis.transformedSinks.flatMap({c =>
      if (analysis.sinkPorts(c).size == 1)
        analysis.sinkPorts(c).map(rt => (rt, rt.copy(ref = s"${c}_sink").field("bits")))
      else
        analysis.sinkPorts(c).map(rt => (rt, rt.copy(ref = s"${c}_sink").field("bits").field(FAMEChannelAnalysis.removeCommonPrefix(rt.ref, c)._1)))
    })
    val sourceRenames = analysis.transformedSources.flatMap({c =>
      if (analysis.sourcePorts(c).size == 1)
        analysis.sourcePorts(c).map(rt => (rt, rt.copy(ref = s"${c}_source").field("bits")))
      else
        analysis.sourcePorts(c).map(rt => (rt, rt.copy(ref = s"${c}_source").field("bits").field(FAMEChannelAnalysis.removeCommonPrefix(rt.ref, c)._1)))
    })

    def renamePorts(suffix: String, lookup: ModuleTarget => Map[String, Seq[Port]])
                   (mT: ModuleTarget): Seq[(ReferenceTarget, ReferenceTarget)] = {
        lookup(mT).toSeq.flatMap({ case (cName, pList) =>
          pList.map({ port =>
            val decoupledTarget = mT.ref(s"${cName}${suffix}").field("bits")
            if (pList.size == 1)
              (mT.ref(port.name), decoupledTarget)
            else
              (mT.ref(port.name), decoupledTarget.field(FAMEChannelAnalysis.removeCommonPrefix(port.name, cName)._1))
          })
        })
    }
    def renameModelInputs: ModuleTarget => Seq[(ReferenceTarget, ReferenceTarget)] = renamePorts("_sink", analysis.modelInputChannelPortMap)
    def renameModelOutputs: ModuleTarget => Seq[(ReferenceTarget, ReferenceTarget)] = renamePorts("_source", analysis.modelOutputChannelPortMap)

    val modelPortRenames = analysis.transformedModules.flatMap(renameModelInputs) ++
                           analysis.transformedModules.flatMap(renameModelOutputs)

    (sinkRenames ++ sourceRenames ++ modelPortRenames).foreach({ case (old, decoupled) => renames.record(old, decoupled) })
    renames
  }

  def transformTop(top: DefModule, analysis: FAMEChannelAnalysis): Module = top match {
    case Module(info, name, ports, body) =>
      val transformedPorts = ports.filterNot(p => analysis.staleTopPorts.contains(analysis.topTarget.ref(p.name))) ++
        analysis.transformedSinks.map(c => Port(NoInfo, s"${c}_sink", Input, analysis.getSinkHostDecoupledChannelType(c))) ++
        analysis.transformedSources.map(c => Port(NoInfo, s"${c}_source", Output, analysis.getSourceHostDecoupledChannelType(c)))
      val transformedStmts = Seq(body.map(updateNonChannelConnects(analysis))) ++
        analysis.transformedSinks.map({c => Connect(NoInfo, analysis.wsubToSinkPort(c), WRef(s"${c}_sink"))}) ++
        analysis.transformedSources.map({c => Connect(NoInfo, WRef(s"${c}_source"), analysis.wsubToSourcePort(c))})
      Module(info, name, transformedPorts, Block(transformedStmts))
  }

  override def execute(state: CircuitState): CircuitState = {
    val c = state.circuit
    val analysis = new FAMEChannelAnalysis(state, FAME1Transform)
    // TODO: pick a value that does not collide
    implicit val triggerName = "finishing"
    val transformedModules = c.modules.map {
      case m: Module if (m.name == c.main) => transformTop(m, analysis)
      case m: Module if (analysis.transformedModules.contains(ModuleTarget(c.main,m.name))) => FAMEModuleTransformer(m, analysis)
      case m: Module if (analysis.syncNativeModules.contains(ModuleTarget(c.main, m.name))) => PatientSSMTransformer(m, analysis)
      case m => m
    }
    state.copy(circuit = c.copy(modules = transformedModules), renames = Some(hostDecouplingRenames(analysis)))
  }
}
