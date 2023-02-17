// See LICENSE for license details.

package midas.passes

import midas.widgets.{BridgeAnnotation, ClockBridgeModule}
import midas.widgets.{PipeBridgeChannel, ClockBridgeChannel, ReadyValidBridgeChannel}
import midas.passes.fame.{PromoteSubmodule, PromoteSubmoduleAnnotation, FAMEChannelConnectionAnnotation, RTRenamer}
import midas.passes.fame.{PipeChannel, TargetClockChannel, DecoupledReverseChannel, DecoupledForwardChannel}

import firrtl._
import firrtl.ir._
import firrtl.passes.{InferTypes, ResolveKinds}
import firrtl.traversals.Foreachers._
import firrtl.passes.wiring.WiringInfo
import firrtl.annotations._

import scala.collection.mutable

private[passes] class BridgeExtraction extends firrtl.Transform {

  case class BridgeInstance(target: InstanceTarget) extends SingleTargetAnnotation[InstanceTarget] {
    def targets = Seq(target)
    def duplicate(n: InstanceTarget) = this.copy(n)
  }

  override def name = "[MIDAS] Bridge Extraction"
  def inputForm = MidForm
  def outputForm = MidForm

  private val bridgeMods = new mutable.HashSet[String]()
  private val wiringAnnos  = mutable.ArrayBuffer[WiringInfo]()
  private val topPorts     = mutable.ArrayBuffer[Port]()

  // Taken from extract model -- recursively calls PromoteSubmodule until all
  // bridges live at the top of the module hierarchy
  def promoteBridges(state: CircuitState): CircuitState = {
    val anns = state.annotations.flatMap {
      case a @ BridgeInstance(it) if (it.module != it.circuit) => Seq(a, PromoteSubmoduleAnnotation(it))
      case a => Seq(a)
    }
    if (anns.toSeq == state.annotations.toSeq) {
      state
    } else {
      promoteBridges((new PromoteSubmodule).runTransform(state.copy(annotations = anns)))
    }
  }

  // Propogate Bridge ModuleAnnotations to Instance Annotations
  def annotateInstances(state: CircuitState): CircuitState = {
    val c = state.circuit
    val iGraph  =  new firrtl.analyses.InstanceGraph(c)
    // Collect all bridge modules
    val bridgeAnnos = state.annotations.collect({ case anno: BridgeAnnotation => anno.target })
    val bridgeModules = bridgeAnnos.map(_.module)

    // Get a list of all bridge instances using iGraph
    val bridgeInsts: Seq[Seq[WDefInstance]] = bridgeModules.flatMap(e => iGraph.findInstancesInHierarchy(e).map(_.reverse))

    // Generate instance annotations to drive promoteBridges()
    val instAnnos = bridgeInsts.collect({ case bridge :: parent :: restOfPath =>
      BridgeInstance(InstanceTarget(c.main, parent.module, Nil, bridge.name, bridge.module))
    })
    state.copy(annotations = state.annotations ++ instAnnos)
  }

  def getBridgeConnectivity(portInstMapping: mutable.ArrayBuffer[(String, String)],
                              insts: mutable.ArrayBuffer[(String, String)])
                             (stmt: Statement): Unit = {
    stmt match {
      case c @ Connect(_, WSubField(WRef(topName, _, InstanceKind, _), portName, _, _), 
                          WRef(bridgeInstName, _, InstanceKind, _)) =>
        portInstMapping += (portName -> bridgeInstName)
      case i @ WDefInstance(_, name, module, _) if name != "realTopInst" =>
        insts += (name -> module)
      case o => Nil
    }
    stmt.foreach(getBridgeConnectivity(portInstMapping, insts))
  }

  // Moves bridge annotations from BlackBox onto newly created ports
  def commuteBridgeAnnotations(state: CircuitState): CircuitState = {
    val c = state.circuit
    val topModule = c.modules.find(_.name == c.main).get
    // Collect all bridge modules
    val bridgeAnnos = mutable.ArrayBuffer[BridgeAnnotation]()

    val otherAnnos = state.annotations.flatMap({
      case anno: BridgeAnnotation => bridgeAnnos += anno; None
      case otherAnno => Some(otherAnno)
    })

    val bridgeAnnoMap = bridgeAnnos.map(anno => anno.target.module -> anno ).toMap

    val portInstPairs = new mutable.ArrayBuffer[(String, String)]()
    val instList      = new mutable.ArrayBuffer[(String, String)]()
    topModule.foreach(getBridgeConnectivity(portInstPairs, instList))
    val instMap = instList.toMap

    val clockBridgeInsts = instList
        .map(inst => inst._1 -> bridgeAnnoMap(instMap(inst._1)))
        .collect({ case (inst, cb: BridgeAnnotation) if cb.widgetClass == classOf[ClockBridgeModule].getName => inst })

    val bridgeInstMessage = "You must use a single ClockBridge instance to generate clocks for your simulated system."
    assert(clockBridgeInsts.nonEmpty, s"No ClockBridge instances found. ${bridgeInstMessage}")
    assert(clockBridgeInsts.size == 1,
      s"Multiple ClockBridge instances found: ${clockBridgeInsts.mkString("\n")} ${bridgeInstMessage}")

    val ioAnnotations = portInstPairs.flatMap({ case (port, inst) =>
      val bridge = bridgeAnnoMap(instMap(inst))
      val updatedBridgeAnno = bridge.toIOAnnotation(port)

      def buildRenamer(rTs: Seq[ReferenceTarget]) = {
        def updateRT(rT: ReferenceTarget): ReferenceTarget =
          ModuleTarget(rT.circuit, rT.circuit).ref(port).field(rT.ref)
        RTRenamer.exact(RenameMap(Map((rTs.map(rT => rT -> Seq(updateRT(rT)))):_*)))
      }

      val bridgeFCCAAnnos : AnnotationSeq = bridge.bridgeChannels.flatMap({
        case PipeBridgeChannel(name, clock, sinks, sources, latency) => {
          val renamer = buildRenamer(sinks ++ sources ++ Seq(clock))

          Seq(FAMEChannelConnectionAnnotation(
            s"${port}_${name}",
            PipeChannel(latency),
            Some(renamer(clock)),
            if (sources.isEmpty) { None } else { Some(sources.map(renamer(_))) },
            if (sinks.isEmpty) { None } else { Some(sinks.map(renamer(_))) }
          ))
        }
        case ClockBridgeChannel(name, sinks, clocks, clockMFMRs) => {
          val renamer = buildRenamer(sinks)

          Seq(FAMEChannelConnectionAnnotation(
            s"${port}_${name}",
            channelInfo = TargetClockChannel(clocks, clockMFMRs),
            clock = None,
            sinks = Some(sinks.map(renamer(_))),
            sources = None
          ))
        }
        case ReadyValidBridgeChannel(fwdName, revName, clock, sinks, sources, valid, ready) => {
          val renamer = buildRenamer(sinks ++ sources ++ Seq(clock, valid, ready))

          val clockRT = renamer(clock)
          val validRT = renamer(valid)
          val readyRT = renamer(ready)

          Seq(
            FAMEChannelConnectionAnnotation(
                s"${port}_${fwdName}",
                if (sinks.isEmpty) {
                  DecoupledForwardChannel.source(validRT, readyRT)
                } else {
                  DecoupledForwardChannel.sink(validRT, readyRT)
                },
                Some(clockRT),
                if (sources.isEmpty) { None } else { Some(sources.map(renamer(_))) },
                if (sinks.isEmpty) { None } else { Some(sinks.map(renamer(_))) }
            ),
            FAMEChannelConnectionAnnotation(
                s"${port}_${revName}",
                DecoupledReverseChannel,
                Some(clockRT),
                if (sources.isEmpty) { Some(Seq(readyRT)) } else { None },
                if (sinks.isEmpty) { Some(Seq(readyRT)) } else { None }
            )
          )
        }
      })
      Seq(updatedBridgeAnno) ++ bridgeFCCAAnnos
    })

    state.copy(annotations = otherAnnos ++ ioAnnotations)
  }

  def execute(state: CircuitState): CircuitState = {
    val c = state.circuit
    val circuitNS = Namespace(c)

    // First create a dummy top into which we'll promote bridges
    val topWrapperName = circuitNS.newName("DummyTop")
    val topInstance = WDefInstance("realTopInst", c.main)
    val topWrapper = Module(NoInfo, topWrapperName, Seq(), Block(Seq(topInstance)))
    val wrappedTopState = state.copy(circuit = c.copy(modules = topWrapper +: c.modules, main = topWrapperName))

    // Annotate all bridge instances
    val instAnnoedState = annotateInstances(wrappedTopState)

    def normalize(state: CircuitState): CircuitState = {
      val cx = RemoveTrivialPartialConnects.run(InferTypes.run(ResolveKinds.run(state.circuit)))
      state.copy(circuit = cx)
    }

    // Promote all modules that are annotated as bridges
    val promotedState = normalize(promoteBridges(instAnnoedState))

    // Propogate bridge annotations to the IO created on the true top module
    val commutedState = commuteBridgeAnnotations(promotedState)

    // Remove all bridge modules and the dummy top wrapper
    val modulesToRemove = bridgeMods += topWrapperName
    val prunedCircuit = commutedState.circuit.copy(
      modules = promotedState.circuit.modules.filterNot(m => modulesToRemove(m.name)),
      main = c.main
    )
    commutedState.copy(circuit = prunedCircuit)
  }
}
