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
  def isFiring: Expression = And(isReady, isValid)
  def replacePortRef(wr: WRef): WSubField = {
    if (ports.size > 1) {
      WSubField(WSubField(WRef(asPort), "bits"), FAMEChannelAnalysis.removeCommonPrefix(wr.name, name)._1)
    } else {
      WSubField(WRef(asPort), "bits")
    }
  }
}

case class FAME1ClockChannel(name: String, ports: Seq[Port]) extends FAME1Channel {
  val direction = Input
  val portName = s"${name}_sink" // clock channel port on model sinks clocks
}

trait FAME1DataChannel extends FAME1Channel {
  def clockDomainEnable: Expression
  def firedReg: DefRegister
  def isFired = WRef(firedReg)
  def isFiredOrFiring = Or(isFired, isFiring)
  def updateFiredReg(finishing: WRef): Statement = {
    Connect(NoInfo, isFired, Mux(finishing, Negate(clockDomainEnable), isFiredOrFiring, BoolType))
  }
}

case class FAME1InputChannel(
  name: String,
  clockDomainEnable: Expression,
  ports: Seq[Port],
  firedReg: DefRegister) extends FAME1DataChannel {
  val direction = Input
  val portName = s"${name}_sink"
  def setReady(finishing: WRef): Statement = {
    Connect(NoInfo, isReady, And(finishing, Negate(isFired)))
  }
}

case class FAME1OutputChannel(
  name: String,
  clockDomainEnable: Expression,
  ports: Seq[Port],
  firedReg: DefRegister) extends FAME1DataChannel {
  val direction = Output
  val portName = s"${name}_source"
  def setValid(finishing: WRef, ccDeps: Iterable[FAME1InputChannel]): Statement = {
    Connect(NoInfo, isValid, And.reduce(ccDeps.map(_.isValid).toSeq :+ Negate(isFired)))
  }
}

// Multi-clock timestep:
// When finishing is high, dequeue token from clock channel
// - Use to initialize isFired for all channels (with negation)
// - Finishing is gated with clock channel valid
object FAMEModuleTransformer {
  def apply(m: Module, analysis: FAMEChannelAnalysis): Module = {
    // Step 0: Bookkeeping for port structure conventions
    implicit val ns = Namespace(m)
    val mTarget = ModuleTarget(analysis.circuit.main, m.name)
    val clocks: Seq[Port] = m.ports.filter(_.tpe == ClockType)
    val portsByName = m.ports.map(p => p.name -> p).toMap
    assert(clocks.length >= 1)

    // Multi-clock management step 1: Add host clock + reset ports, finishing wire
    // TODO: Should finishing be a WrappedComponent?
    // TODO: Avoid static naming convention.
    val hostReset = Port(NoInfo, WrapTop.hostResetName, Input, BoolType)
    val hostClock = Port(NoInfo, WrapTop.hostClockName, Input, ClockType)
    val finishing = DefWire(NoInfo, "targetCycleFinishing", BoolType)
    assert(ns.tryName(hostReset.name) && ns.tryName(hostClock.name) && ns.tryName(finishing.name))
    def hostFlagReg(suggestName: String, resetVal: UIntLiteral = UIntLiteral(0)): DefRegister = {
      DefRegister(NoInfo, ns.newName(suggestName), BoolType, WRef(hostClock), WRef(hostReset), resetVal)
    }

    // Multi-clock management step 2: Build clock flags and clock channel
    def isClockChannel(info: (String, (Option[Port], Seq[Port]))) = info match {
      case (_, (clk, ports)) => clk.isEmpty && ports.forall(_.tpe == ClockType)
    }

    val clockChannel = analysis.modelInputChannelPortMap(mTarget).find(isClockChannel) match {
      case Some((name, (None, ports))) => FAME1ClockChannel(name, ports)
      case Some(_) => ??? // Clock channel cannot have 
      case None => ??? // Clock channel is mandatory for now
    }

    def tokenizeClockRef(wr: WRef): Expression = wr match {
      case WRef(name, ClockType, PortKind, _) => DoPrim(PrimOps.AsUInt, Seq(clockChannel.replacePortRef(wr)), Nil, BoolType)
    }

    // Multi-clock management step 4: Generate clock buffers for all target clocks
    val targetClockBufs: Seq[SignalInfo] = clockChannel.ports.map { en =>
      val enableReg = hostFlagReg(s"${en.name}_enabled", resetVal = UIntLiteral(1))
      val buf = WDefInstance(ns.newName(s"${en.name}_buffer"), DefineAbstractClockGate.blackbox.name)
      val clockFlag = tokenizeClockRef(WRef(en))
      val connects = Block(Seq(
        Connect(NoInfo, WRef(enableReg), Mux(WRef(finishing), clockFlag, WRef(enableReg), BoolType)),
        Connect(NoInfo, WSubField(WRef(buf), "I"), WRef(hostClock)),
        Connect(NoInfo, WSubField(WRef(buf), "CE"), And(WRef(enableReg), WRef(finishing)))))
      SignalInfo(Block(Seq(enableReg, buf)), connects, WSubField(WRef(buf), "O", ClockType, MALE))
    }

    // Multi-clock management step 5: Generate target clock substitution map
    def asWE(p: Port) = WrappedExpression.we(WRef(p))
    val replaceClocksMap = (clockChannel.ports.map(p => asWE(p)) zip targetClockBufs.map(_.ref)).toMap

    // LI-BDN transformation step 1: Build channels
    // TODO: get rid of the analysis calls; we just need connectivity & annotations
    val portDeps = analysis.connectivity(m.name)

    def genMetadata(info: (String, (Option[Port], Seq[Port]))) = info match {
      case (cName, (Some(clock), ports)) =>
        // must be driven by one clock input port
        // TODO: this should not include muxes in connectivity!
        val srcClockPorts = portDeps.getEdges(clock.name).map(portsByName(_))
        assert(srcClockPorts.size == 1)
        val clockRef = WRef(srcClockPorts.head)
        val clockFlag = tokenizeClockRef(clockRef)
        val firedReg = hostFlagReg(suggestName = ns.newName(s"${cName}_fired"))
        (cName, clockFlag, ports, firedReg)
      case (cName, (None, ports)) => ??? // clock is mandatory for now
    }

    // LinkedHashMap.from is 2.13-only :(
    def stableMap[K, V](contents: Iterable[(K, V)]) = new LinkedHashMap[K, V] ++= contents

    // Have to filter out the clock channel from the input channels
    val inChannelInfo = analysis.modelInputChannelPortMap(mTarget).filterNot(isClockChannel(_)).toSeq
    val inChannelMetadata = inChannelInfo.map(genMetadata(_))
    val inChannels = inChannelMetadata.map((FAME1InputChannel.apply _).tupled)
    val inChannelMap = stableMap(inChannels.flatMap(c => c.ports.map(p => p.name -> c)))

    val outChannelInfo = analysis.modelOutputChannelPortMap(mTarget).toSeq
    val outChannelMetadata = outChannelInfo.map(genMetadata(_))
    val outChannels = outChannelMetadata.map((FAME1OutputChannel.apply _).tupled)
    val outChannelMap = stableMap(outChannels.flatMap(c => c.ports.map(p => p.name -> c)))

    // LI-BDN transformation step 2: find combinational dependencies among channels
    val ccDeps = new LinkedHashMap[FAME1OutputChannel, LinkedHashSet[FAME1InputChannel]]
    portDeps.getEdgeMap.collect({ case (o, iSet) if outChannelMap.contains(o) =>
      // Only add input channels, since output might depend on output RHS ref
      ccDeps.getOrElseUpdate(outChannelMap(o), new LinkedHashSet[FAME1InputChannel]) ++= iSet.flatMap(inChannelMap.get(_))
    })

    // LI-BDN transformation step 3: transform ports (includes new clock ports)
    val transformedPorts = hostClock +: hostReset +: (clockChannel +: inChannels ++: outChannels).map(_.asPort)

    // LI-BDN transformation step 4: replace port and clock references and gate state updates
    def onExpr(expr: Expression): Expression = expr match {
      case iWR @ WRef(name, tpe, PortKind, MALE) if tpe != ClockType =>
        // Generally MALE references to ports will be input channels, but RTL may use
        // an assignment to an output port as something akin to a wire, so check output ports too.
        inChannelMap.getOrElse(name, outChannelMap(name)).replacePortRef(iWR)
      case oWR @ WRef(name, tpe, PortKind, FEMALE) if tpe != ClockType =>
        outChannelMap(name).replacePortRef(oWR)
      case cWR @ WRef(name, ClockType, PortKind, MALE) =>
        replaceClocksMap(WrappedExpression.we(cWR))
      case e => e map onExpr
    }

    def onStmt(stmt: Statement): Statement = stmt match {
      case Connect(_, WRef(_, ClockType, PortKind, _), _) => EmptyStmt // don't drive clock outputs
      case s => s map onStmt map onExpr
    }

    val updatedBody = onStmt(m.body)

    // LI-BDN transformation step 5: add firing rules for output channels, trigger end of cycle
    // This is modified for multi-clock, as each channel fires only when associated clock is enabled
    val allFiredOrFiring = And.reduce(outChannels.map(_.isFiredOrFiring) ++ inChannels.map(_.isValid))

    val channelStateRules = (inChannels ++ outChannels).map(c => c.updateFiredReg(WRef(finishing)))
    val inputRules = inChannels.map(i => i.setReady(WRef(finishing)))
    val outputRules = outChannels.map(o => o.setValid(WRef(finishing), ccDeps(o)))
    val topRules = Seq(
      Connect(NoInfo, clockChannel.isReady, allFiredOrFiring),
      Connect(NoInfo, WRef(finishing), And(allFiredOrFiring, clockChannel.isValid)))

    // Statements have to be conservatively ordered to satisfy declaration order
    val decls = finishing +: targetClockBufs.map(_.decl) ++: (inChannels ++ outChannels).map(_.firedReg)
    val assigns = targetClockBufs.map(_.assigns) ++ channelStateRules ++ inputRules ++ outputRules ++ topRules
    Module(m.info, m.name, transformedPorts, Block(decls ++: updatedBody +: assigns))
  }
}

class FAMETransform extends Transform {
  def inputForm = LowForm
  def outputForm = HighForm

  def updateNonChannelConnects(analysis: FAMEChannelAnalysis)(stmt: Statement): Statement = stmt.map(updateNonChannelConnects(analysis)) match {
    case wi: WDefInstance if (analysis.transformedModules.contains(analysis.moduleTarget(wi))) =>
      val clockConn = Connect(NoInfo, WSubField(WRef(wi), WrapTop.hostClockName), WRef(analysis.hostClock.ref, ClockType))
      val resetConn = Connect(NoInfo, WSubField(WRef(wi), WrapTop.hostResetName), WRef(analysis.hostReset.ref, BoolType))
      Block(Seq(wi, clockConn, resetConn))
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

    def renamePorts(suffix: String, lookup: ModuleTarget => Map[String, (Option[Port], Seq[Port])])
                   (mT: ModuleTarget): Seq[(ReferenceTarget, ReferenceTarget)] = {
        lookup(mT).toSeq.flatMap({ case (cName, (clockOption, pList)) =>
          pList.map({ port =>
            val decoupledTarget = mT.ref(s"${cName}${suffix}").field("bits")
            if (pList.size == 1)
              (mT.ref(port.name), decoupledTarget)
            else
              (mT.ref(port.name), decoupledTarget.field(FAMEChannelAnalysis.removeCommonPrefix(port.name, cName)._1))
          })
          // TODO: rename clock to nothing, since it is deleted
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
      case m => m // TODO (Albert): revisit this; currently, not transforming nested modules
    }
    state.copy(circuit = c.copy(modules = transformedModules), renames = Some(hostDecouplingRenames(analysis)))
  }
}
