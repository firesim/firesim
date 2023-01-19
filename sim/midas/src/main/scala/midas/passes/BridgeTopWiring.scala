package midas.passes

import scala.collection.mutable

import firrtl._
import firrtl.analyses.InstanceGraph
import firrtl.annotations._
import firrtl.ir._
import firrtl.transforms.DontTouchAllTargets
import firrtl.transforms.TopWiring.{TopWiringAnnotation, TopWiringTransform, TopWiringOutputFilesAnnotation}
import firrtl.options.Dependency
import firrtl.stage.Forms
import firrtl.stage.transforms.Compiler
import TargetToken.{Instance, OfModule}

import midas.passes.fame.RTRenamer
import midas.targetutils.FAMEAnnotation

/**
  * Provides signals for the transform to wire to the top-level of hte module hierarchy.
  *
  * @param target The signal to be plumbed to the top
  *
  * @param clock The clock to which this signal is sychronous. This will _not_ be wired.
  *
  */
case class BridgeTopWiringAnnotation(target: ReferenceTarget, clock: ReferenceTarget) extends Annotation with FAMEAnnotation with DontTouchAllTargets {
  def update(renames: RenameMap): Seq[BridgeTopWiringAnnotation] =
    Seq(this.copy(RTRenamer.exact(renames)(target), RTRenamer.exact(renames)(clock)))

  def toWiringAnnotation(prefix: String): TopWiringAnnotation = TopWiringAnnotation(target.pathlessTarget.toNamed, prefix)
}

/**
  * Provides reference targets to the newly generated top-level IO and a
  * generated output-clock port that output is synchronous to.
  *
  * @param pathlessSource The original target passed in a [[BridgeTopWiringAnnotation]]
  *
  * @param absoluteSource An absolute reference target to the particular
  *        instance of that signal that drives the new output. NB: A single [[BridgeTopWiringAnnotation]]
  *        will generate as many output annotations as there are instances of the pathless source.
  *
  * @param topSink The new top-level port the source has been connected to
  *
  * @param srcClockPort The input clock associated with the source
  *
  * @param clockPort The new output clock port the topSink port to be referenced in FCCAs
  *
  */
case class BridgeTopWiringOutputAnnotation(
    pathlessSource: ReferenceTarget,
    absoluteSource: ReferenceTarget,
    topSink: ReferenceTarget,
    srcClockPort: ReferenceTarget,
    sinkClockPort: ReferenceTarget) extends Annotation with FAMEAnnotation {

  def update(renames: RenameMap): Seq[BridgeTopWiringOutputAnnotation] = {
    val renameExact = RTRenamer.exact(renames)
    Seq(BridgeTopWiringOutputAnnotation(renameExact(pathlessSource),
                                        renameExact(absoluteSource),
                                        renameExact(topSink),
                                        renameExact(srcClockPort),
                                        renameExact(sinkClockPort)))
  }
}

object BridgeTopWiring {
  class NoClockSourceFoundException(data: ReferenceTarget, clock: ReferenceTarget)
      extends Exception(s"Could not determine the source of clock ${clock} for target-to-wire ${data}")
}

/**
  * A utility transform used to implement features that are finely distributed
  * through out the target, such as assertion and printf synthesis. This
  * transform preforms most of the circuit modifications and analysis to emit
  * BridgeIOAnnotations and FCCAs directly. For this pass to function
  * correctly, the clock bridge must already be extracted.
  *
  * For each BridgeTopWiringAnnotation, this transform:
  * 1) Wires out every instance of that signal to a unique port in the top-level module
  *    These will be referenced by Bridge FCCAs and will become simulation channels.
  * 2) Determines the source clock (these are now inputs on the top-level module) to which each that port is synchronous
  *
  * For each clock that is synchronous with at least one output port:
  * 1) Loop that clock back to a new output port (Bridge FCCAs will point at this clock)
  *
  * Finally emit a [[BridgeTopWiringOutputAnnotation]] for each created data-output port.
  *
  * @param prefix Provides the top-wiring prefix
  *
  */

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
      (ReferenceTarget, ReferenceTarget) = absoluteSourceRT(iMaps) -> portRT()
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
    val wiredState = new Compiler(Forms.MidForm :+ Dependency[TopWiringTransform], Forms.MidForm)
      .execute(state.copy(annotations = topWiringAnnos ++ state.annotations))

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
    val loweredState = new Compiler(Seq(Dependency(FindClockSources)), Forms.MidForm)
      .execute(stateToAnalyze)

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
                                                   body  = Block(m.body, addedConnects.toSeq:_*))
      case o => o
    }))

    // Step 5: Generate output annotations
    val outputAnnotations = (for ((localRT, absRTs) <- localToAbsSource) yield {
      absRTs.map { case (absSourceRT, absClockRT) =>
        clockSourceMap(absClockRT) match {
          case Some(clock) => BridgeTopWiringOutputAnnotation(localRT, absSourceRT, absSrcToPort(absSourceRT), clock, src2sinkClockMap(clock))
          case None => throw new BridgeTopWiring.NoClockSourceFoundException(absSourceRT, absClockRT)
        }
      }
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
