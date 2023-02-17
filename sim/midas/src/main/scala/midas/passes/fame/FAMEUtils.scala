// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import ir._
import traversals.Foreachers._
import analyses.InstanceGraph
import transforms.CheckCombLoops

import annotations._

import scala.collection
import collection.mutable
import collection.mutable.{LinkedHashSet, LinkedHashMap, MultiMap}

object RTRenamer {
  // TODO: determine order for multiple renames, or just check of == 1 rename?
  def exact(renames: RenameMap): (ReferenceTarget => ReferenceTarget) = {
    { rt =>
      val renameMatches = renames.get(rt).getOrElse(Seq(rt)).collect({ case rt: ReferenceTarget => rt })
      assert(renameMatches.length == 1,
        s"renameMatches for ${rt} is ${renameMatches.length}, not 1. Matches:" + renameMatches.mkString("\n"))
      renameMatches.head
    }
  }

  def apply(renames: RenameMap): (ReferenceTarget => Seq[ReferenceTarget]) = {
    { rt => renames.get(rt).getOrElse(Seq(rt)).collect({ case rt: ReferenceTarget => rt }) }
  }
}

private[fame] object FAMEChannelAnalysis {
  def removeCommonPrefix(a: String, b: String): (String, String) = (a, b) match {
    case (a, b) if (a.length == 0 || b.length == 0) => (a, b)
    case (a, b) if (a.charAt(0) == b.charAt(0)) => removeCommonPrefix(a.drop(1), b.drop(1))
    case (a, b) => (a, b)
  }

  def getHostDecoupledChannelPayloadType(name: String, ports: Seq[Port]): Type = {
    val fields = ports.map(p => Field(removeCommonPrefix(p.name, name)._1, Default, p.tpe))
    if (fields.size > 1) {
      new BundleType(fields.toSeq)
    } else {
      fields.head.tpe
    }
  }

  def getHostDecoupledChannelType(name: String, ports: Seq[Port]): Type = Decouple(getHostDecoupledChannelPayloadType(name, ports))
}

trait ChannelFlow {
  def suffix: String
}
case object ChannelSource extends ChannelFlow { def suffix = "_source" }
case object ChannelSink extends ChannelFlow { def suffix = "_sink" }

/**
  * This contains methods useful for analyzing inter-model connectivity and decoding
  * information carried by various FAMEAnnotations into more usable forms.
  *
  * At different points during compilation different members be safely used. Three 
  * points of interest:
  *
  * 1) Post-[[FAMEDefaults]]: [[FAMEChannelConnectionAnnotation]]s are fully defined (provided between
  * all inter-model and model-IO connections in the top-level) after [[FAMEDefaults]] has been
  * executed.
  *
  * 2) Post-[[InferModelPorts]]: [[FAMEChannelPortsAnnotation]] are fully defined on all model modules.
  *
  * 3) Post-[[FAMETransform]]: All target firrtl.ir.[[Port]]s have been mapped to
  * decoupled equivalents on _all_ models, regardless of their eventual implementation.
  *
  *
  * TODO: Break this up.
  */

private[fame] class FAMEChannelAnalysis(val state: CircuitState) {
  // TODO: only transform submodules of model modules
  // TODO: add renames!
  val circuit = state.circuit
  val topTarget = ModuleTarget(circuit.main, circuit.main)
  def moduleTarget(m: DefModule) = topTarget.copy(module = m.name)
  def moduleTarget(wi: WDefInstance) = topTarget.copy(module = wi.module)

  // The presence of a clock port is used to indicate whether a module contains state
  def stateful(m: DefModule) = m.ports.exists(_.tpe == ClockType)

 /*
  * A list of stateful modules that doesn't include blackboxes.
  * This is needed since only pure-FIRRTL modules get transformed.
  * Blackboxes' ports remain unchanged, as does their Verilog source.
  */
  val syncNativeModules = circuit.modules.collect({ case m: Module if stateful(m) => moduleTarget(m) }).toSet

  val moduleNodes = new LinkedHashMap[ModuleTarget, DefModule]
  val portNodes = new LinkedHashMap[ReferenceTarget, Port]
  circuit.modules.foreach({
    m => {
      val mTarget = ModuleTarget(circuit.main, m.name)
      moduleNodes(mTarget) = m
      m.ports.foreach({
        p => portNodes(mTarget.ref(p.name)) = p
      })
    }
  })

  // Used to check if any modules contains synchronous blackboxes in its sub-hierarchy
  private val syncBlackboxes = circuit.modules.collect({ case bb: ExtModule if stateful(bb) => bb.name }).toSet
  lazy val iGraph = new InstanceGraph(circuit)
  lazy val mGraph = iGraph.graph.transformNodes[String](_.module)
  def containsSyncBlackboxes(m: Module) = mGraph.reachableFrom(m.name).exists(syncBlackboxes.contains(_))

  lazy val connectivity = (new CheckCombLoops).analyze(state)

  val channels = new LinkedHashSet[String]
  val channelsByPort = new LinkedHashMap[ReferenceTarget, mutable.Set[String]] with MultiMap[ReferenceTarget, String]
  val transformedModules = new LinkedHashSet[ModuleTarget]
  state.annotations.collect({
    case fta @ FAMETransformAnnotation(mt) =>
      transformedModules += mt
    case fca: FAMEChannelConnectionAnnotation =>
      channels += fca.globalName
      fca.clock.foreach({ rt => channelsByPort.addBinding(rt, fca.globalName) })
      fca.sinks.toSeq.flatten.foreach({ rt => channelsByPort.addBinding(rt, fca.globalName) })
      fca.sources.toSeq.flatten.foreach({ rt => channelsByPort.addBinding(rt, fca.globalName) })
  })

  private val moduleOfInstance = new LinkedHashMap[String, String]
  val topConnects = new LinkedHashMap[ReferenceTarget, ReferenceTarget]
  val topBridgeLoopbackConnects = new LinkedHashMap[ReferenceTarget, ReferenceTarget] 
  val inputChannels = new LinkedHashMap[ModuleTarget, mutable.Set[String]] with MultiMap[ModuleTarget, String]
  val outputChannels = new LinkedHashMap[ModuleTarget, mutable.Set[String]] with MultiMap[ModuleTarget, String]
  getTopConnects(moduleNodes(topTarget).asInstanceOf[Module].body)

  def getTopConnects(stmt: Statement): Unit = stmt match {
    case WDefInstance(_, iname, mname, _) =>
      moduleOfInstance(iname) = mname
    case Connect(_, WRef(tpname, ClockType, _, _), WSubField(WRef(iname, _, _, _), pname, ClockType, _)) =>
      // Clock connect, don't make any channels
      // The clock in a FAMEChannelConnectionAnnotation is the clock from model to bridge
      val tpRef = topTarget.ref(tpname)
      val child = topTarget.instOf(iname, moduleOfInstance(iname))
      topConnects(tpRef) = child.ref(pname)
    case Connect(_, WRef(tpname, _, _, _), WSubField(WRef(iname, _, _, _), pname, _, _)) =>
      val tpRef = topTarget.ref(tpname)
      channelsByPort.get(tpRef).foreach({ cnames =>
        val child = topTarget.instOf(iname, moduleOfInstance(iname))
        topConnects(tpRef) = child.ref(pname)
        cnames foreach { c => outputChannels.addBinding(child.ofModuleTarget, c) }
      })
    case Connect(_, WSubField(WRef(iname, _, _, _), pname, _, _), WRef(tpname, _, _, _)) =>
      val tpRef = topTarget.ref(tpname)
      channelsByPort.get(tpRef).foreach({ cnames =>
        assert(cnames.size == 1)
        val child = topTarget.instOf(iname, moduleOfInstance(iname))
        topConnects(tpRef) = child.ref(pname)
        inputChannels.addBinding(child.ofModuleTarget, cnames.head)
      })
    case Connect(_, WRef(lhstpname, _, _, _), WRef(rhstpname, _, _, _)) =>
      val lhsTpRef = topTarget.ref(lhstpname)
      val rhsTpRef = topTarget.ref(rhstpname)
      channelsByPort.get(lhsTpRef).foreach({ cnames =>
        assert(cnames.size == 1)
        topBridgeLoopbackConnects(lhsTpRef) = rhsTpRef
      })

    case s => s.foreach(getTopConnects)
  }

  val transformedSinks = new LinkedHashSet[String]
  val transformedSources = new LinkedHashSet[String]
  private val loopbackRHSToSinkChannel = new LinkedHashMap[ReferenceTarget, String]
  private val loopbackSourceChannelToLHS = new LinkedHashMap[String, ReferenceTarget]
  val sinkModel = new LinkedHashMap[String, InstanceTarget]
  val sourceModel = new LinkedHashMap[String, InstanceTarget]
  /**
    *  Looks up a model instance based on the desired channel flow and channel name
    *
    * @param cName The name of the channel
    * @param flow For SourceFlow look up the instance that provides the tokens,
    *             For SinkFlow    "               ""       consumes  "   "
    */
  def cNameToModelInst(cName: String, flow: ChannelFlow): InstanceTarget =
    if (flow == ChannelSource) sourceModel(cName) else sinkModel(cName)

  // clock ports don't go from one model to the other -> only one map needed
  val modelClockPort = new LinkedHashMap[String, Option[ReferenceTarget]]
  val sinkPorts = new LinkedHashMap[String, Seq[ReferenceTarget]]
  val sourcePorts = new LinkedHashMap[String, Seq[ReferenceTarget]]
  val staleTopPorts = new LinkedHashSet[ReferenceTarget]
  state.annotations.collect({
    case fca: FAMEChannelConnectionAnnotation =>
      channels += fca.globalName

      // Clock port always gets recorded and marked for deletion in FAME transform
      modelClockPort(fca.globalName) = fca.clock
      staleTopPorts ++= fca.clock

      val sinks = fca.sinks.toSeq.flatten
      sinkPorts(fca.globalName) = sinks
      sinks.headOption collect {
        case rt if topBridgeLoopbackConnects.values.toSet(rt) =>
          staleTopPorts ++= sinks
          loopbackRHSToSinkChannel(rt) = fca.globalName
        case rt if transformedModules.contains(ModuleTarget(rt.circuit, topConnects(rt).encapsulatingModule)) =>
          assert(!topConnects(rt).isLocal) // need instance info
          sinkModel(fca.globalName) = topConnects(rt).targetParent.asInstanceOf[InstanceTarget]
          transformedSinks += fca.globalName
          staleTopPorts ++= sinks
      }

      val sources = fca.sources.toSeq.flatten
      sourcePorts(fca.globalName) = sources
      sources.headOption collect {
        case rt if topBridgeLoopbackConnects.isDefinedAt(rt) =>
          staleTopPorts ++= sources
          loopbackSourceChannelToLHS(fca.globalName) = rt
        case rt if transformedModules.contains(ModuleTarget(rt.circuit, topConnects(rt).encapsulatingModule)) =>
          assert(!topConnects(rt).isLocal) // need instance info
          sourceModel(fca.globalName) = topConnects(rt).targetParent.asInstanceOf[InstanceTarget]
          transformedSources += fca.globalName
          // FCCAs can encode a fan out from one source to many sinks. Do this only once.
          if (!staleTopPorts.contains(sources.head)) {
            staleTopPorts ++= sources
          }
      }
  })

  // Now construct tuples of channel names corresponding to bridge loopbacks
  val transformedLoopbacks = for ((srcCName, srcRT) <- loopbackSourceChannelToLHS) yield {
    (srcCName, loopbackRHSToSinkChannel(topBridgeLoopbackConnects(srcRT)))
  }
  val (transformedLoopbackSources, transformedLoopbackSinks) = transformedLoopbacks.unzip match {
    case (a,b) => (a.toSet, b.toSet)
  }

  val hostClock = state.annotations.collect({ case FAMEHostClock(rt) => rt }).head
  val hostReset = state.annotations.collect({ case FAMEHostReset(rt) => rt }).head
  lazy val targetClockChInfo = state.annotations.collectFirst({
    case FAMEChannelConnectionAnnotation(_,chInfo: TargetClockChannel,_,_,_) => chInfo
  }).get


  private def irPortFromGlobalTarget(mt: ModuleTarget)(rt: ReferenceTarget): Option[Port] = {
    val modelPort = topConnects(rt).pathlessTarget
    Some(modelPort).filter(_.module == mt.module).map(portNodes(_))
  }

  def portsByInputChannel(mTarget: ModuleTarget): Map[String, (Option[Port], Seq[Port])] = {
    val iChannels = inputChannels.get(mTarget).toSet.flatten
    iChannels.map({ cname =>
      (cname, (modelClockPort(cname).flatMap(irPortFromGlobalTarget(mTarget)),
        sinkPorts(cname).map(rt => irPortFromGlobalTarget(mTarget)(rt).get)))
    }).toMap
  }

  def portsByOutputChannel(mTarget: ModuleTarget): Map[String, (Option[Port], Seq[Port])] = {
    val oChannels = outputChannels.get(mTarget).toSet.flatten
    oChannels.map({
      cname => (cname, (modelClockPort(cname).flatMap(irPortFromGlobalTarget(mTarget)), sourcePorts(cname).map(rt => irPortFromGlobalTarget(mTarget)(rt).get)))
    }).toMap
  }

  lazy val modelPorts = {
    val mPorts = new LinkedHashMap[ModuleTarget, mutable.Set[FAMEChannelPortsAnnotation]] with MultiMap[ModuleTarget, FAMEChannelPortsAnnotation]
    state.annotations.collect({
      case fcp @ FAMEChannelPortsAnnotation(_, _, port :: ps) => mPorts.addBinding(port.moduleTarget, fcp)
    })
    mPorts
  }

  // Looks up all FAMEChannelPortAnnotations bound to a model module, to generate a Map
  // from channel name to clock option and port list
  // Note: This can only be run after [[InferModelPorts]] has been executed.
  private def genModelChannelPortMap(direction: Option[Direction])(mTarget: ModuleTarget): Map[String, (Option[Port], Seq[Port])] = {
    modelPorts(mTarget).collect({
      case FAMEChannelPortsAnnotation(name, clock, ports) if direction == None || portNodes(ports.head).direction == direction.get =>
        (name, (clock.map(portNodes(_)), ports.map(portNodes(_))))
    }).toMap
  }

  def modelInputChannelPortMap: ModuleTarget => Map[String, (Option[Port], Seq[Port])]  = genModelChannelPortMap(Some(Input))
  def modelOutputChannelPortMap: ModuleTarget => Map[String, (Option[Port], Seq[Port])] = genModelChannelPortMap(Some(Output))
  def modelChannelPortMap: ModuleTarget => Map[String, (Option[Port], Seq[Port])]       = genModelChannelPortMap(None)

  def getSinkHostDecoupledChannelType(cName: String): Type = {
    FAMEChannelAnalysis.getHostDecoupledChannelType(cName, sinkPorts(cName).map(portNodes(_)))
  }

  def getSourceHostDecoupledChannelType(cName: String): Type = {
    FAMEChannelAnalysis.getHostDecoupledChannelType(cName, sourcePorts(cName).map(portNodes(_)))
  }

  /**
    * [[FAMEChannelConnectionAnnotations]] linking model _instances_ are fully
    * defined (after [[FAMEDefaults]]) before the host ports on the _modules_ themselves are known)
    * This helper class analyzes [[FAMEChannelConnectionAnnotation]]s to
    * produce a host port list for the module
    * - Used to produce [[FAMEChannelPortsAnnotation]] annotations in [[InferModelPorts]], afterwhich
    *   [[FAMEChannelPortsAnnotation]] are fully defined on module ports
    * - Reran to look up port names on model instances
    *
    * Two [[FAMEChannelConnectionAnnotation]]s may share a matching set of sources or sinks on
    * a model _module_ in two cases:
    * 1) They point at two different instances of the model's module.
    * 2) They point at the same instance, but have identical source lists. Here, the
    *    instance's output fans out to multiple sink models.
    *
    * Currently, two [[FAMEChannelConnectionAnnotation]]s cannot have partially overlapping sources or sinks
    * as it would make the port definition ambiguous (without introducing additional complexity).
    *
    * @param mTarget The model modeule to analyze
    */
  class ModulePortDeduper(val mTarget: ModuleTarget) {

    val inputChannelDedups = new LinkedHashMap[String, String]
    val outputChannelDedups = new LinkedHashMap[String, String]

    private val visitedLeafPort = new LinkedHashSet[Port]()
    private val visitedChannelPort = new LinkedHashMap[(Option[Port], Seq[Port]), String]()

    private def channelIsDuplicate(ps: (Option[Port], Seq[Port])): Boolean = visitedChannelPort.contains(ps)
    private def channelSharesPorts(ps: (Option[Port], Seq[Port])): Boolean = ps match {
      case (clk, ports) => ports.exists(visitedLeafPort(_)) // clock can be shared
    }

    private def dedupPortLists(
        dedups: LinkedHashMap[String, String],
        pList: Map[String, (Option[Port], Seq[Port])]): Map[String, (Option[Port], Seq[Port])] = pList.flatMap({
      case (cName, (_, Nil)) => throw new RuntimeException(s"Channel ${cName} is empty (has no associated ports)")
      case (cName, clockAndPorts) if channelSharesPorts(clockAndPorts) && !channelIsDuplicate(clockAndPorts) =>
        throw new RuntimeException("Channel definition has partially overlapping ports with existing channel definition")
      case (cName, clockAndPorts) if channelIsDuplicate(clockAndPorts) =>
        dedups(cName) = visitedChannelPort(clockAndPorts)
        None
      case (cName, (clock, ports)) =>
        // Try to come up with a good name for this collection of target ports.
        // This will need to be re-evaluated once channel aggregation is implemented
        val chPortName = ports match {
          case Nil => ??? // Caught above
          // For single-element channels. Retain the name of the port
          case port :: Nil => port.name
          // Multi-element channels are only emitted by bridges; their channel
          // names are good candidates.
          case ports => cName
        }
        visitedChannelPort((clock, ports)) = chPortName
        visitedLeafPort ++= clock
        visitedLeafPort ++= ports
        dedups(cName) = chPortName
        Some(chPortName, (clock, ports))
      }).toMap

    val inputPortMap = dedupPortLists(inputChannelDedups, portsByInputChannel(mTarget))
    val outputPortMap = dedupPortLists(outputChannelDedups, portsByOutputChannel(mTarget))

    val completePortMap = inputPortMap ++ outputPortMap
    /**
      * For a given channel look up the associated port name on its source or
      * sink model. Loopback channels can have both source and sink
      * ports, the flow parameter disambiguates this.
      *
      * @param cName The name of the channel
      * @param flow [[ChannelSource]] => look at source model; [[ChannelSink]] => Look at the sink model
      */
    def cNameToPortName(cName: String, flow: ChannelFlow): String =
      if (flow == ChannelSource) outputChannelDedups(cName) else inputChannelDedups(cName)

    def prettyPrint(): String = {
      val modulePreamble = s"Deduper for module ${mTarget.module}"
      val outputPreamble = s"  Output Ports"
      val inputPreamble = s"  Input Ports"
      val outputs = outputChannelDedups.groupBy(_._2).map { case (pName, channels) => 
        Seq(s"    Port ${pName} drives channels:") ++: channels.map { case (ch, _) =>  s"      ${ch}" }
      }
      val inputs = inputChannelDedups.groupBy(_._2).map { case (pName, channels) => 
        s"""|    Port ${pName} sinks channel:
            |      ${channels.head._1}""".stripMargin
      }
      val lines = Seq(modulePreamble, inputPreamble) ++: inputs ++: Seq(outputPreamble) ++: outputs.flatten
      lines.mkString("\n")
    }
  }

  lazy val modulePortDedupers = transformedModules.map((mT: ModuleTarget) => new ModulePortDeduper(mT))
  lazy val mTargetToDeduper = modulePortDedupers.map(mD => mD.mTarget -> mD).toMap

  def chNameToModelPortName(flow: ChannelFlow)(cName: String): String = {
    val modelInst = cNameToModelInst(cName, flow)
    val portName = mTargetToDeduper(modelInst.ofModuleTarget).cNameToPortName(cName, flow)
    s"${portName}${flow.suffix}"
  }

  def chNameToModelSourcePortName: String => String = chNameToModelPortName(ChannelSource)
  def chNameToModelSinkPortName: String => String   = chNameToModelPortName(ChannelSink)

  // Generates WSubField node pointing at a model instance from the channel name
  // and an iTarget to  model instance
  private def wsubToPort(flow: ChannelFlow)(cName: String): WSubField = {
    val modelInst = cNameToModelInst(cName, flow)
    WSubField(WRef(modelInst.instance), chNameToModelPortName(flow)(cName))
  }

  def wsubToSourcePort: String => WSubField = wsubToPort(ChannelSource)
  def wsubToSinkPort: String => WSubField = wsubToPort(ChannelSink)
}
