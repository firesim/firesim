package midas.passes

import scala.collection.mutable

import firrtl._
import firrtl.analyses.InstanceGraph
import firrtl.annotations._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.transforms.TopWiring.{TopWiringAnnotation, TopWiringTransform, TopWiringOutputFilesAnnotation}
import TargetToken.{Instance, OfModule}

import midas.passes.fame.RTRenamer


case class BridgeTopWiringAnnotation(target: ReferenceTarget, clock: ReferenceTarget) extends Annotation {
  def update(renames: RenameMap): Seq[BridgeTopWiringAnnotation] =
    Seq(this.copy(RTRenamer.exact(renames)(target), RTRenamer.exact(renames)(clock)))

  def toWiringAnnotation(prefix: String): TopWiringAnnotation = TopWiringAnnotation(target.pathlessTarget.toNamed, prefix)
}

case class BridgeTopWiringOutputAnnotation(
    pathlessSource: ReferenceTarget,
    absoluteSource: ReferenceTarget,
    topSink: ReferenceTarget,
    clockPort: ReferenceTarget) extends Annotation {

  def update(renames: RenameMap): Seq[BridgeTopWiringOutputAnnotation] = {
    val renameExact = RTRenamer.exact(renames)
    Seq(BridgeTopWiringOutputAnnotation(renameExact(pathlessSource),
                                        renameExact(absoluteSource),
                                        renameExact(topSink),
                                        renameExact(clockPort)))
  }
}

class BridgeTopWiring(val prefix: String) extends firrtl.Transform {
  def inputForm = MidForm
  def outputForm = MidForm
  case class TopWiringMapping(src: ComponentName, instPath: Seq[String]) {
    // TopWiring doesn't return references to the top-level ports; we need to reconstruct those
    // from an instance path and the prefix provided
    val topMT = ModuleTarget(src.module.circuit.name, src.module.circuit.name)

    // The RT to then new top-level port
    def portRT(): ReferenceTarget = topMT.ref(prefix + (instPath :+ src.name).mkString("_"))

    // A complete-path reference target to the source that drives this new output
    def absoluteSourceRT(childInstGraph: Map[String, Map[Instance, OfModule]]): ReferenceTarget = {
      instPath.foldLeft[IsModule](topMT)((target, instName) => {
        val moduleName = target match {
          case iT: InstanceTarget => iT.ofModule
          case mT: ModuleTarget   => mT.module
        }
        val instModuleName = childInstGraph(moduleName)(Instance(instName))
        target.instOf(instName, instModuleName.value)
      }).ref(src.name)
    }

    def getSourceSinkPair(iMaps: Map[String, Map[Instance, OfModule]]):
      (ReferenceTarget, ReferenceTarget) = absoluteSourceRT(iMaps) -> portRT
  }

  def execute(state: CircuitState): CircuitState = {

    require(state.annotations.collect({ case t: TopWiringAnnotation => t }).isEmpty,
      "CircuitState cannot have existing TopWiring annotations before BridgeTopWiring.")

    val inputAnnos = state.annotations.collect({ case a: BridgeTopWiringAnnotation => a })
    val localClockMap = inputAnnos.map(anno => anno.target -> anno.clock).toMap

    // Step 1: Invoke top wiring
    // Hacky: Instead of generated output files, instead sneak out the mappings from the TopWiring
    // transform.
    var topLevelOutputs = Seq[TopWiringMapping]()
    def wiringAnnoOutputFunc(td: String,
                             mappings:  Seq[((ComponentName, Type, Boolean, Seq[String], String), Int)],
                             state: CircuitState): CircuitState = {
      topLevelOutputs = mappings.unzip._1.map(inscrutable5Tuple => TopWiringMapping(inscrutable5Tuple._1, inscrutable5Tuple._4.dropRight(1)))
      state
    }

    val topWiringOFileAnno = TopWiringOutputFilesAnnotation("unused", wiringAnnoOutputFunc)
    val topWiringAnnos = topWiringOFileAnno +: inputAnnos.map(_.toWiringAnnotation(prefix)).distinct
    val wiredState = new TopWiringTransform().execute(state.copy(annotations = topWiringAnnos ++ state.annotations))

    // Step 2: Reconstruct a map from source RT to newly wired reference target
    val instanceMaps = new InstanceGraph(wiredState.circuit).getChildrenInstanceMap
                                                            .map({ case (m, set) => m.value -> set.toMap })
                                                            .toMap
    // localRTtoAbsRTs
    val localToAbsSource = topLevelOutputs.groupBy(_.src).map({ case (src, mappings) =>
      val localRT = src.toTarget
      val absoluteRTs = mappings.map({ m =>
        val srcRT = m.absoluteSourceRT(instanceMaps)
        val clockRT = srcRT.copy(ref = localClockMap(localRT).ref)
        (srcRT, clockRT)
      })
      (localRT, absoluteRTs)
    }).toMap

    val allAbsClockRTs = localToAbsSource.values.flatMap(_.unzip._2)

    // Maps complete source RT to the portRT it has been wired to
    val absSrcToPort = topLevelOutputs.map(_.getSourceSinkPair(instanceMaps)).toMap

    // Step 3: Do clock analysis using complete paths to clocks
    val findClockSourceAnnos = allAbsClockRTs.map(src => FindClockSourceAnnotation(src)).toSeq
    val stateToAnalyze = wiredState.copy(annotations = findClockSourceAnnos)
    val loweredState = Seq(new ResolveAndCheck,
                           new MiddleFirrtlToLowFirrtl,
                           FindClockSources).foldLeft(stateToAnalyze)((state, xform) => xform.transform(state))

    val clockSourceMap = loweredState.annotations.collect({
      case ClockSourceAnnotation(qT, source) => qT -> source
    }).toMap


    // Step 4: Add new clock ports
    val c = wiredState.circuit
    val uniqueSources = clockSourceMap.values.flatten.toSeq.distinct
    val addedPorts = new mutable.ArrayBuffer[Port]()
    val addedConnects = new mutable.ArrayBuffer[Connect]()
    val ns = Namespace(c.modules.find(_.name == c.main).get)
    val src2sinkClockMap = (for (clock <- uniqueSources) yield {
      val portName = ns.newName(s"${prefix}${clock.ref}")
      val port = Port(NoInfo, portName, Output, ClockType)
      addedConnects += Connect(NoInfo, WRef(port), WRef(clock.ref))
      addedPorts    += port
      clock -> clock.copy(ref = portName)
    }).toMap

    val updatedCircuit = c.copy(modules = c.modules.map({
      case m: Module if m.name == c.main => m.copy(ports = m.ports ++ addedPorts,
                                                   body  = Block(m.body, addedConnects:_*))
      case o => o
    }))

    // Step 5: Generate output annotations
    val outputAnnotations = (for ((localRT, absRTs) <- localToAbsSource) yield {
      absRTs.flatMap({ case (absSourceRT, absClockRT) =>
        clockSourceMap(absClockRT).map(clock =>
          BridgeTopWiringOutputAnnotation(localRT, absSourceRT, absSrcToPort(absSourceRT), src2sinkClockMap(clock)))
      })
    }).flatten

    val updatedAnnotations = outputAnnotations.toSeq ++ wiredState.annotations.flatMap({
      case s: TopWiringAnnotation => None
      case s: TopWiringOutputFilesAnnotation => None
      case s: BridgeTopWiringAnnotation => None
      case o => Some(o)
    })

    wiredState.copy(circuit = updatedCircuit, annotations = updatedAnnotations)
  }
}
