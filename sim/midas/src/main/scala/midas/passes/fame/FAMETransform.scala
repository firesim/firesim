// See LICENSE for license details.

package midas.passes.fame

import java.io.{PrintWriter, File}

import firrtl._
import ir._
import Mappers._
import firrtl.Utils.{BoolType, kind, one}
import firrtl.passes.MemPortUtils
import firrtl.transforms.DontTouchAnnotation
import annotations._
import scala.collection.mutable
import mutable.{LinkedHashSet, LinkedHashMap}
import freechips.rocketchip.util.DensePrefixSum

import midas.passes._
import midas.widgets.HasTimestampConstants


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
  def isValid: Expression
  def hasTimestamp: Boolean
  def asHostModelPort: Option[Port] = None
  def replacePortRef(wr: WRef): Expression
}

trait InputChannel {
  this: FAME1Channel =>
  val direction = Input
  val portName = s"${name}_sink"
  def setReady(readyCond: Expression): Statement
}

trait HasModelPort {
  this: FAME1Channel =>
  override def isValid = WSubField(WRef(asHostModelPort.get), "valid", BoolType)
  def isReady = WSubField(WRef(asHostModelPort.get), "ready", BoolType)
  def isFiring: Expression = And(isReady, isValid)
  def setReady(advanceCycle: Expression): Statement = Connect(NoInfo, isReady, advanceCycle)
  def payloadRef(): Expression = {
    if (hasTimestamp) {
      WSubField(WSubField(WRef(asHostModelPort.get), "bits"), "data")
    } else {
      WSubField(WRef(asHostModelPort.get), "bits")
    }
  }

  override def asHostModelPort: Option[Port] = {
    val tpe = FAMEChannelAnalysis.getHostDecoupledChannelType(name, ports, hasTimestamp)
    direction match {
      case Input => Some(Port(NoInfo, s"${name}_sink", Input, tpe))
      case Output => Some(Port(NoInfo, s"${name}_source", Output, tpe))
    }
  }

  def replacePortRef(wr: WRef): Expression = {
    if (ports.size == 1) payloadRef else WSubField(payloadRef, FAMEChannelAnalysis.removeCommonPrefix(wr.name, name)._1)
  }
}

trait FAME1DataChannel extends FAME1Channel with HasModelPort {
  def clockDomainEnable: Expression
  def firedReg: DefRegister
  def isFired = WRef(firedReg)
  def isFiredOrFiring = Or(isFired, isFiring)
  def updateFiredReg(finishing: Expression): Statement = {
    Connect(NoInfo, isFired, Mux(finishing, Negate(clockDomainEnable), isFiredOrFiring, BoolType))
  }
}

case class FAME1ClockChannel(name: String, ports: Seq[Port]) extends FAME1Channel with InputChannel with HasModelPort {
  assert(ports.size == 1)
  val hasTimestamp = true
  def getTimestampRef(): WSubField = {
      WSubField(WSubField(WRef(asHostModelPort.get), "bits"), "time")
  }
}

case class VirtualClockChannel(targetClock: Port) extends FAME1Channel with InputChannel {
  val hasTimestamp = false
  val name = "VirtualClockChannel"
  val ports = Seq(targetClock)
  val isValid: Expression = UIntLiteral(1)
  def setReady(advanceCycle: Expression): Statement = EmptyStmt
  def replacePortRef(wr: WRef): Expression = UIntLiteral(1)
}

case class FAME1InputChannel(
  name: String,
  clockDomainEnable: Expression,
  ports: Seq[Port],
  firedReg: DefRegister,
  hasTimestamp: Boolean) extends FAME1DataChannel with InputChannel {
  override def setReady(advanceCycle: Expression): Statement = {
    Connect(NoInfo, isReady, And(clockDomainEnable, advanceCycle))
  }
}

case class FAME1OutputChannel(
  name: String,
  clockDomainEnable: Expression,
  ports: Seq[Port],
  firedReg: DefRegister,
  hasTimestamp: Boolean) extends FAME1DataChannel {
  val direction = Output
  val portName = s"${name}_source"
  def setValid(finishing: WRef, ccDeps: Iterable[FAME1InputChannel]): Statement = {
    Connect(NoInfo, isValid, And.reduce(ccDeps.map(_.isValid).toSeq :+ Negate(isFired)))
  }

  def setTimestamp(currentTime: Expression): Option[Statement] = if (hasTimestamp) {
    Some(Connect(NoInfo, WSubField(WSubField(WRef(asHostModelPort.get), "bits"), "time"), currentTime))
  } else {
    None
  }
}


// Multi-clock timestep:
// When finishing is high, dequeue token from clock channel
// - Use to initialize isFired for all channels (with negation)
// - Finishing is gated with clock channel valid
object FAMEModuleTransformer extends HasTimestampConstants {
  def apply(m: Module, analysis: FAMEChannelAnalysis): (Module, Iterable[Annotation]) = {
    // Step 0: Bookkeeping for port structure conventions
    implicit val ns = Namespace(m)
    val mTarget = ModuleTarget(analysis.circuit.main, m.name)
    val clocks: Seq[Port] = m.ports.filter(_.tpe == ClockType)
    val portsByName = m.ports.map(p => p.name -> p).toMap
    assert(clocks.length >= 1)

    // Multi-clock management step 1: Add host clock + reset ports, finishing wire
    // TODO: Should finishing be a WrappedComponent?
    // TODO: Avoid static naming convention.
    implicit val hostReset = new HostReset(WrapTop.hostResetName)
    implicit val hostClock = new HostClock(WrapTop.hostClockName)
    import HostRTLImplicitConversions._
    val finishing = DefWire(NoInfo, "targetCycleFinishing", BoolType)
    assert(ns.tryName(finishing.name))

    // Multi-clock management step 2: Generate clock scheduling logic
    def isClockChannel(info: (String, (Option[Port], Seq[Port]))) = info match {
      case (_, (clk, ports)) =>
        require(clk.nonEmpty || ports.size == 1, s"Clock channels must carry a single clock. Got: ${ports.size}\n.")
        clk.isEmpty && ports.forall(_.tpe == ClockType)
    }

    val existingClockChannels = analysis.modelInputChannelPortMap(mTarget).collect {
      case chInfo if isClockChannel(chInfo) => FAME1ClockChannel(chInfo._1, chInfo._2._2)
    }

    val isHubModel = existingClockChannels.nonEmpty
    val clockChannels = if (isHubModel) existingClockChannels else Seq(VirtualClockChannel(clocks.head))
    val targetClockPorts = clockChannels.map(_.ports.head)

    // Multi-clock management step 2: Build clock flags and clock channel
    val clockSchedulingStmts = new mutable.ArrayBuffer[Statement]
    val clockSchedulingPorts = new mutable.ArrayBuffer[Port]
    val clockSchedulingAnnos = new mutable.ArrayBuffer[Annotation]
    // Not used in the satellite models; will be optimized away
    val s2_time = TimestampRegister("s2_time")
    clockSchedulingStmts += s2_time
    val (clockEnables, nextEdgeAvailable, s1_valid, s2_valid) = if (isHubModel) {
      // Control signal generation to simulation master
      val numTargetClocks = existingClockChannels.size
      // From master: A driver-imposed limit on how far the simulation may advance
      val timeHorizon =  Port(NoInfo, ns.newName("timeHorizon"), Input, timestampFType)
      // To master: Metadata on firing clocks to calculate FMR and track simulation progress
      val simAdvancing = Port(NoInfo, ns.newName("simulationAdvancing"), Output, BoolType)
      val simTime = Port(NoInfo, ns.newName("nextSimulationTime"), Output, timestampFType)
      val scheduledClocks = Port(NoInfo, ns.newName("scheduledClocks"), Output, BoolType)

      val s1_valid = HostFlagRegister("s1_valid")
      val s2_valid = HostFlagRegister("s2_valid")
      val s1_enable = DefNode(NoInfo, ns.newName("s1_enable"), Or(Negate(WRef(s1_valid)), WRef(finishing)))

      // Front-end of the pipeline. based on all input tokens determine if forward progress can be made.
      val advanceToTime = DefWire(NoInfo, ns.newName("advanceToTime"), timestampFType)
      val clockSchedulers = existingClockChannels.map(ch => new ClockScheduler(ch, WRef(s1_enable), WRef(advanceToTime))).toSeq
      val minReductionNodes = new mutable.ArrayBuffer[DefNode]
      val allClocksDefinedUntil = DefNode(NoInfo, ns.newName("allClocksDefinedUntil"),
        DensePrefixSum(clockSchedulers.map(_.definedUntil).toSeq)({ case (a, b) =>
          val node = DefNode(NoInfo, ns.newTemp, Mux(Lt(a,b), a, b))
          minReductionNodes += node
          WRef(node)
        }).last)

      // So long as edges occur before the controller provided horizon,
      // schedule them; otherwise advance as far as we've been directed.
      val advanceToTimeConn = Connect(NoInfo, WRef(advanceToTime),
        Mux(Lt(WRef(allClocksDefinedUntil), WRef(timeHorizon)), WRef(allClocksDefinedUntil), WRef(timeHorizon)))
      val clockEnables = clockSchedulers.map(_.posedgeEnable).toSeq
      val nextEdgeAvailable = DefNode(NoInfo, ns.newName("nextEdgeAvailable"), clockEnables.reduce(Or.apply))


      val s1_time = TimestampRegister("s1_time")
      val willAdvance = DefNode(NoInfo, ns.newName("willAdvance"), Neq(WRef(advanceToTime), WRef(s1_time)))
      val s1_time_update = Connect(
        NoInfo,
        WRef(s1_time),
        Mux(WRef(s1_enable), WRef(advanceToTime), WRef(s1_time)))
      val s2_time_update = Connect(
        NoInfo,
        WRef(s2_time),
        Mux(WRef(finishing), WRef(s1_time), WRef(s2_time)))
      val s1_valid_update = Connect(NoInfo, WRef(s1_valid), Mux(WRef(s1_enable), WRef(willAdvance), WRef(s1_valid)))
      val s2_valid_update = Connect(NoInfo, WRef(s2_valid), Mux(WRef(finishing), WRef(s1_valid), WRef(s2_valid)))
      // Declarations
      clockSchedulingStmts ++= s1_valid +: s2_valid +: s1_enable +: s1_time +: advanceToTime +: willAdvance +: clockSchedulers.flatMap(_.stmts) ++: nextEdgeAvailable +: minReductionNodes :+ allClocksDefinedUntil
      // Connections
      clockSchedulingStmts ++= Seq(advanceToTimeConn, s1_time_update, s2_time_update, s1_valid_update, s2_valid_update)
      // Control port handling
      clockSchedulingPorts ++= Seq(timeHorizon, simAdvancing, scheduledClocks, simTime)
      clockSchedulingStmts ++= Seq(
        Connect(NoInfo, WRef(simAdvancing), And(WRef(willAdvance), WRef(s1_enable))),
        Connect(NoInfo, WRef(simTime), WRef(advanceToTime)),
        Connect(NoInfo, WRef(scheduledClocks), clockEnables.reduce(Or.apply)))

      clockSchedulingAnnos += SimulationControlAnnotation(Map(
          "timeHorizon" -> PortMetadata(mTarget, timeHorizon),
          "simAdvancing" -> PortMetadata(mTarget, simAdvancing),
          "simTime" -> PortMetadata(mTarget, simTime),
          "scheduledClocks" -> PortMetadata(mTarget, scheduledClocks)))
      (clockEnables, WRef(nextEdgeAvailable), WRef(s1_valid), WRef(s2_valid))
    } else {
      (Seq(UIntLiteral(1)), UIntLiteral(1), UIntLiteral(1), UIntLiteral(1))
    }

    case class TargetClockMetadata(
      targetSourcePort: Port,
      clockEnable: Expression,
      clock: Expression)

    val (clockMetadata, clockBufDecls, clockBufAssigns) = (for ((tClock, en) <- targetClockPorts.zip(clockEnables)) yield {
      val enableReg = HostFlagRegister(s"${tClock.name}_enabled")
      val buf = WDefInstance(ns.newName(s"${tClock.name}_buffer"), DefineAbstractClockGate.blackbox.name)
      val connects = Block(Seq(
        Connect(NoInfo, WRef(enableReg), Mux(Or(WRef(finishing), Negate(s1_valid)), en, WRef(enableReg))),
        Connect(NoInfo, WSubField(WRef(buf), "I"), hostClock),
        /*
         *  NB: Failing to keep the target clock-gated during FPGA initialization
         *  can lead to spurious updates or metastability in target state elements.
         *  Keeping all target-clocks gated through the latter stages of FPGA
         *  initialization and reset ensures all target state elements are
         *  initialized with a deterministic set of initial values.
         */
        Connect(NoInfo, WSubField(WRef(buf), "CE"), And(And(WRef(enableReg), Negate(hostReset)), WRef(finishing)))))
      val targetClockCtrl = TargetClockMetadata(tClock, WRef(enableReg), WSubField(WRef(buf), "O", ClockType, SourceFlow))
      (targetClockCtrl, Block(Seq(enableReg, buf)), Block(connects))
    }).unzip3

    // Multi-clock management step 5: Generate target clock substitution map
    def asWE(p: Port) = WrappedExpression.we(WRef(p))

    val replaceClocksMap = clockMetadata.map(c => asWE(c.targetSourcePort) -> c.clock).toMap
    val clockEnableMap = clockMetadata.map(c => WRef(c.targetSourcePort) -> c.clockEnable).toMap

    // LI-BDN transformation step 1: Build channels
    // TODO: get rid of the analysis calls; we just need connectivity & annotations
    val portDeps = analysis.connectivity(m.name)

    def genMetadata(isInput: Boolean)(info: (String, (Option[Port], Seq[Port]))) = info match {
      case (cName, (Some(clock), ports)) =>
        assert(isHubModel || !analysis.channelHasTimestamp(cName),
          s"Channel ${cName} connected to satellite model must not be timestamped.\n")
        // must be driven by one clock input port
        // TODO: this should not include muxes in connectivity!
        val srcClockPorts = portDeps.getEdges(clock.name).map(portsByName(_))
        assert(srcClockPorts.size == 1)
        val clockRef = WRef(srcClockPorts.head)
        val clockFlag = DoPrim(PrimOps.AsUInt, Seq(clockEnableMap(clockRef)), Nil, BoolType)
        val firedReg = HostFlagRegister(s"${cName}_fired")
        (cName, clockFlag, ports, firedReg, analysis.channelHasTimestamp(cName))
      case (cName, (None, ports)) if analysis.channelHasTimestamp(cName) =>
        val firedReg = HostFlagRegister(s"${cName}_fired")
        // Reset timestamped channels whenever making forward progress (any clock is about to fire)
        (cName, s1_valid, ports, firedReg, true)
      case (cName, (None, ports)) =>
        if (!isHubModel) {
          val firedReg = HostFlagRegister(s"${cName}_fired")
          (cName, UIntLiteral(1), ports, firedReg, false)
        } else {
          throw new RuntimeException(s"Channel ${cName} has no associated clock.")
        }
    }

    // LinkedHashMap.from is 2.13-only :(
    def stableMap[K, V](contents: Iterable[(K, V)]) = new LinkedHashMap[K, V] ++= contents

    // Have to filter out the clock channel from the input channels
    val inChannelInfo = analysis.modelInputChannelPortMap(mTarget).filterNot(isClockChannel(_)).toSeq
    val inChannelMetadata = inChannelInfo.map(genMetadata(isInput = true))
    val inChannels = inChannelMetadata.map((FAME1InputChannel.apply _).tupled)
    val inChannelMap = stableMap(inChannels.flatMap(c => c.ports.map(p => p.name -> c)))

    val outChannelInfo = analysis.modelOutputChannelPortMap(mTarget).toSeq
    val outChannelMetadata = outChannelInfo.map(genMetadata(isInput = false))
    val outChannels = outChannelMetadata.map((FAME1OutputChannel.apply _).tupled)
    val outChannelMap = stableMap(outChannels.flatMap(c => c.ports.map(p => p.name -> c)))

    // LI-BDN transformation step 2: find combinational dependencies among channels
    val ccDeps = new LinkedHashMap[FAME1OutputChannel, LinkedHashSet[FAME1InputChannel]]
    portDeps.getEdgeMap.collect({ case (o, iSet) if outChannelMap.contains(o) =>
      // Only add input channels, since output might depend on output RHS ref
      ccDeps.getOrElseUpdate(outChannelMap(o), new LinkedHashSet[FAME1InputChannel]) ++= iSet.flatMap(inChannelMap.get(_))
    })

    // LI-BDN transformation step 3: transform ports (includes new clock ports)
    val transformedPorts = hostClock.port +: hostReset.port +:
      (clockChannels ++: inChannels ++: outChannels).flatMap(_.asHostModelPort) ++:
      clockSchedulingPorts

    // LI-BDN transformation step 4: replace port and clock references and gate state updates
    val clockPortNames = clockMetadata.map(_.targetSourcePort.name).toSet
    def onExpr(expr: Expression): Expression = expr.map(onExpr) match {
      case iWR @ WRef(name, tpe, PortKind, SourceFlow) if tpe != ClockType =>
        // Generally SourceFlow references to ports will be input channels, but RTL may use
        // an assignment to an output port as something akin to a wire, so check output ports too.
        inChannelMap.getOrElse(name, outChannelMap(name)).replacePortRef(iWR)
      case oWR @ WRef(name, tpe, PortKind, SinkFlow) if tpe != ClockType =>
        outChannelMap(name).replacePortRef(oWR)
      case cWR @ WRef(name, ClockType, PortKind, SourceFlow) if clockPortNames(name) =>
        replaceClocksMap(WrappedExpression.we(cWR))
      case e => e map onExpr
    }

    def onStmt(stmt: Statement): Statement = stmt match {
      case Connect(info, WRef(name, ClockType, PortKind, flow), rhs) =>
        // Don't substitute gated clock for LHS expressions
        Connect(info, WRef(name, ClockType, WireKind, flow), onExpr(rhs))
      case s => s map onStmt map onExpr
    }

    val updatedBody = onStmt(m.body)

    // LI-BDN transformation step 5: add firing rules for output channels, trigger end of cycle
    // This is modified for multi-clock, as each channel fires only when associated clock is enabled
    val allFiredOrFiring = And.reduce(outChannels.map(_.isFiredOrFiring) ++ inChannels.map(_.isValid))

    val channelStateRules = outChannels.map(c => c.updateFiredReg(WRef(finishing)))
    val inputRules = inChannels.map(i => i.setReady(And(WRef(finishing), s1_valid)))
    val outputRules = outChannels.map(o => o.setValid(WRef(finishing), ccDeps(o)))
    val topRules = Seq(Connect(NoInfo, WRef(finishing), allFiredOrFiring))
    val outputTimestamps = outChannels.flatMap(o => o.setTimestamp(WRef(s2_time)))
    // Keep output clock ports around as wires just for convenience to keep connects legal
    val clockOutputsAsWires = m.ports.collect { case Port(i, n, Output, ClockType) => DefWire(i, n, ClockType) }

    // Statements have to be conservatively ordered to satisfy declaration order
    val decls = Seq(finishing) ++: clockOutputsAsWires ++: clockSchedulingStmts ++: clockBufDecls ++: outChannels.map(_.firedReg)
    val assigns = clockBufAssigns ++: channelStateRules ++: inputRules ++: outputRules ++: topRules ++: outputTimestamps
    (Module(m.info, m.name, transformedPorts, Block(decls ++: updatedBody +: assigns)), clockSchedulingAnnos)
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
    case Connect(_, lhs, rhs) if (lhs.tpe == ClockType) => EmptyStmt // drop ancillary clock connects
    case Connect(_, WRef(name, _, _, _), _) if (analysis.staleTopPorts.contains(analysis.topTarget.ref(name))) => EmptyStmt
    case Connect(_, _, WRef(name, _, _, _)) if (analysis.staleTopPorts.contains(analysis.topTarget.ref(name))) => EmptyStmt
    case s => s
  }

  def hostDecouplingRenames(analysis: FAMEChannelAnalysis): RenameMap = {
    // Handle renames at the top-level to new channelized names
    val renames = RenameMap()
    def renameTop(suffix: String, lookup: String => Seq[ReferenceTarget])
                 (c: String): Seq[(ReferenceTarget, ReferenceTarget)] =
      lookup(c).map { rt =>
        val baseRT = if (analysis.channelHasTimestamp(c)) {
          rt.copy(ref = s"${c}${suffix}").field("bits").field("data")
        } else {
          rt.copy(ref = s"${c}${suffix}").field("bits")
        }

        if (lookup(c).size == 1)
         (rt, baseRT)
        else
         (rt, baseRT.field(FAMEChannelAnalysis.removeCommonPrefix(rt.ref, c)._1))
      }

    val sinkRenames = analysis.transformedSinks.flatMap(renameTop("_sink", analysis.sinkPorts))
    val sourceRenames = analysis.transformedSources.flatMap(renameTop("_source", analysis.sourcePorts))

    def renamePorts(suffix: String, lookup: ModuleTarget => Map[String, (Option[Port], Seq[Port])])
                   (mT: ModuleTarget): Seq[(ReferenceTarget, ReferenceTarget)] = {
        lookup(mT).toSeq.flatMap({ case (cName, (clockOption, pList)) =>
          pList.map({ port =>
            val decoupledTarget = if (analysis.channelHasTimestamp(cName)) {
              mT.ref(s"${cName}${suffix}").field("bits").field("data")
            } else {
              mT.ref(s"${cName}${suffix}").field("bits")
            }
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

  def staleTopPort(p: Port, analysis: FAMEChannelAnalysis): Boolean = p match {
    case Port(_, name, _, ClockType) => name != WrapTop.hostClockName
    case Port(_, name, _, _) => analysis.staleTopPorts.contains(analysis.topTarget.ref(name))
  }

  def transformTop(top: DefModule, analysis: FAMEChannelAnalysis): Module = top match {
    case Module(info, name, ports, body) =>
      val transformedPorts = ports.filterNot(p => staleTopPort(p, analysis)) ++
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

    val toTransform = analysis.transformedModules
    val (transformedModules, addedAnnotations) = (c.modules.map {
      case m: Module if (m.name == c.main) => (transformTop(m, analysis), Nil)
      case m: Module if (toTransform.contains(ModuleTarget(c.main, m.name))) => FAMEModuleTransformer(m, analysis)
      case m => (m, Nil) // TODO (Albert): revisit this; currently, not transforming nested modules
    }).unzip

    val filteredAnnos = state.annotations.filter {
      case DontTouchAnnotation(rt) if toTransform.contains(rt.moduleTarget) => false
      case _ => true
    }

    val newCircuit = c.copy(modules = transformedModules)
    CircuitState(newCircuit, outputForm, filteredAnnos ++ addedAnnotations.flatten, Some(hostDecouplingRenames(analysis)))
  }
}
