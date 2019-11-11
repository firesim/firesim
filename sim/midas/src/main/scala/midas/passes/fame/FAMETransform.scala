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
  def clockDomainEnable: Port
  def ports: Seq[Port]
  def firedReg: DefRegister
  def tpe: Type = FAMEChannelAnalysis.getHostDecoupledChannelType(name, ports)
  def portName: String
  def asPort: Port = Port(NoInfo, portName, direction, tpe)
  def isReady: Expression = WSubField(WRef(asPort), "ready", BoolType)
  def isValid: Expression = WSubField(WRef(asPort), "valid", BoolType)
  def isFiring: Expression = And(isReady, isValid)
  def isFired = WRef(firedReg)
  def isFiredOrFiring = Or(isFired, isFiring)
  def replacePortRef(wr: WRef): WSubField = {
    if (ports.size > 1) {
      WSubField(WSubField(WRef(asPort), "bits"), FAMEChannelAnalysis.removeCommonPrefix(wr.name, name)._1)
    } else {
      WSubField(WRef(asPort), "bits")
    }
  }
  def updateFiredReg(finishing: WRef): Seq[Statement] = {
    Connect(NoInfo, isFired, Mux(finishing, Negate(WRef(clockDomainEnable)), isFiredOrFiring, BoolType))
  }
}

case class FAME1InputChannel(val name: String, val clockDomainEnable: Port, val ports: Seq[Port], val firedReg: DefRegister) extends FAME1Channel {
  val direction = Input
  val portName = s"${name}_sink"
  def setReady(finishing: WRef): Statement = {
    Connect(NoInfo, isReady, And(finishing, Negate(isFired)))
  }
}

case class FAME1OutputChannel(val name: String, val clockDomainEnable: Port, val ports: Seq[Port], val firedReg: DefRegister) extends FAME1Channel {
  val direction = Output
  val portName = s"${name}_source"
  def setValid(finishing: WRef, ccDeps: Iterable[FAME1InputChannel]): Statement = {
    Connect(NoInfo, isValid, And.reduce(ccDeps.map(_.isValid) :+ Negate(isFired)))
  }
}

object ChannelCCDependencyGraph {
  def apply(m: Module): LinkedHashMap[FAME1OutputChannel, LinkedHashSet[FAME1InputChannel]] = {
    new LinkedHashMap[FAME1OutputChannel, LinkedHashSet[FAME1InputChannel]]
  }
}

// Multi-clock timestep:
// When finishing is high, dequeue token from clock channel
// - Use to initialize isFired for all channels (with negation)
// - Finishing is gated with clock channel valid
object FAMEModuleTransformer {
  def apply(m: Module, analysis: FAMEChannelAnalysis): Module = {
    // Step 0: Bookkeeping for present naming and port structure conventions
    implicit val ns = Namespace(m)
    val clocks = m.ports.filter(_.tpe == ClockType)
    val portsByName = m.ports.map(p => p.name -> p).toMap
    assert(ns.tryName("hostClock") && ns.tryName("hostReset")) // for now, honor this convention
    assert(clocks.length >= 1)

    // Multi-clock management step 1: Add host clock + reset ports, finishing wire
    val hostReset = Port(NoInfo, "hostReset", Input, BoolType)
    val hostClock = Port(NoInfo, "hostClock", Input, ClockType)
    val finishing = DefWire(NoInfo, ns.newName(triggerName), BoolType) // TODO: can this be a WrappedComponent
    def createHostReg(name: String = "host", width: Width = IntWidth(1), resetVal: Expression = UIntLiteral(0, width)): DefRegister = {
      new DefRegister(NoInfo, ns.newName(name), UIntType(width), WRef(hostClock), WRef(hostReset), resetVal)
    }

    // Multi-clock management step 2: Convert all clock ports to enables of same name
    val targetClockEns = clocks.map(tpe = BoolType)

    // Multi-clock management step 3: Generate clock buffers for all target clocks
    val targetClockBufs = targetClockEns.map { en =>
      val enableReg = createHostReg(s"${en.name}_enabled", resetVal = UIntLiteral(1, 1))
      val buf = WDefInstance(DefineAbstractClockGate.blackbox.name, ns.newName(s"${en.name}_buffer"))
      val connects = Seq(
        Connect(NoInfo, WRef(enableReg), Mux(WRef(finishing), WRef(en), WRef(enableReg), BoolType)),
        Connect(NoInfo, WSubField(WRef(buf), "I"), WRef(hostClock)),
        Connect(NoInfo, WSubField(WRef(buf), "CE"), And(WRef(enableReg), WRef(finishing))))
      SignalInfo(Block(Seq(enableReg, buf)), connects, WSubField(WRef(buf), "O", ClockType, MALE))
    }

    // Multi-clock management step 4: Generate target clock substitution map
    val replaceClocksMap = (targetClockEns.map(p => we(p)) zip targetClockBufs.map(_.ref)).toMap

    // LI-BDN transformation step 1: Build channels
    val portDeps = analysis.connectivity(m.name)
    val inChannels = (analysis.modelInputChannelPortMap(mTarget)).map({
      case(cName, Some(clock), ports) =>
        val clockDomainEnable = portsByName(portDeps(clock.name)).copy(tpe = BoolType)
        new FAME1InputChannel(cName, clockDomainEnable, ports)
      case (_, None, _) => ??? // clocks are currently mandatory in channels
    })
    val inChannelMap = new LinkedHashMap[String, FAME1InputChannel] ++
      (inChannels.flatMap(c => c.ports.map(p => (p.name, c))))

    val outChannels = analysis.modelOutputChannelPortMap(mTarget).map({
      case(cName, Some(clock), ports) =>
        val clockDomainEnable = portsByName(portDeps(clock.name)).copy(tpe = BoolType)
        val firedReg = createHostReg(name = ns.newName(s"${cName}_fired"))
        new FAME1OutputChannel(cName, clockDomainEnable, ports, firedReg)
      case (_, None, _) => ??? // clocks are currently mandatory in channels
    })
    val outChannelMap = new LinkedHashMap[String, FAME1OutputChannel] ++
      (outChannels.flatMap(c => c.ports.map(p => (p.name, c))))

    // LI-BDN transformation step 2: find combinational dependencies among channels
    val ccDeps = new LinkedHashMap[FAME1OutputChannel, LinkedHashSet[FAME1InputChannel]]
    portDeps.getEdgeMap.collect({ case (o, iSet) if outChannelMap.contains(o) =>
      // Only add input channels, since output might depend on output RHS ref
      ccDeps.getOrElseUpdate(outChannelMap(o), new LinkedHashSet[FAME1InputChannel]) ++= iSet.flatMap(inChannelMap.get(_))
    })

    // LI-BDN transformation step 3: transform ports (includes new clock ports)
    val transformedPorts = Seq(hostClock, hostReset) ++ targetClockEns ++ (inChannels ++ outChannels).map(_.asPort)

    // LI-BDN transformation step 4: replace port and clock references and gate state updates
    def onExpr(expr: Expression): Expression = expr match {
      case wr @ WRef(name, tpe, PortKind, MALE) if tpe != ClockType =>
        // Generally MALE references to ports will be input channels, but RTL may use
        // an assignment to an output port as something akin to a wire, so check output ports too.
        inChannelMap.getOrElse(name, outChannelMap(name)).replacePortRef(iWR)
      case oWR @ WRef(name, tpe, PortKind, FEMALE) if tpe != ClockType =>
        outChannelMap(name).replacePortRef(oWR)
      case cWR @ WRef(name, ClockType, PortKind, MALE) =>
        replaceClocksMap(wr)
      case e => e map onExpr
    }

    val transformedStmts = Seq(m.body.map(_.map(onExpr)))

    // LI-BDN transformation step 5: add firing rules for output channels, trigger end of cycle
    // This is modified for multi-clock, as each channel fires only when associated clock is enabled
    val allFiredOrFiring = And.reduce(outChannels.map(_.isFiredOrFiring) ++ inChannels.map(_.isValid))
    val clockChannelReady = ???
    val clockChannelValid = ???

    val channelStateRules = (inChannels ++ outChannels).map(c => c.updateFiredReg)
    val inputRules = inChannels.flatMap(i => i.setReady(WRef(finishing)))
    val outputRules = outChannels.flatMap(o => o.setValid(WRef(finishing), ccDeps(o)))
    val topRules = Seq(
      Connect(NoInfo, clockChannelReady, allFiredOrFiring),
      Connect(NoInfo, WRef(finishing), And(allFiredOrFiring, clockChannelValid)))

    // Statements have to be conservatively ordered to satisfy declaration order
    val decls = finishing +: targetClockBufs.map(_.decl) ++ (inChannels ++ outChannels).map(_.firedReg)
    val assigns = targetClockBufs.map(_.assigns) ++ channelStateRules ++ inputRules ++ outputRules ++ topRules
    Module(m.info, m.name, transformedPorts, Block(decls ++: transformedStmts +: assigns))
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
