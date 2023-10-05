// See LICENSE for license details.

package midas.passes

import firrtl._
import firrtl.ir._
import firrtl.annotations._
import midas.widgets._
import midas.targetutils._
import midas.passes.fame.{WireChannel, FAMEChannelConnectionAnnotation}

import java.io._
import collection.mutable

/**
  * Take the annotated target and drive with plus args bridge(s)
  */
class PlusArgsTransform extends Transform {
  def inputForm: CircuitForm = LowForm
  def outputForm: CircuitForm = MidForm
  override def name = "[Golden Gate] PlusArgs Transform"

  private def implementViaBridge(
      state: CircuitState,
      annos: Seq[PlusArgsFirrtlAnnotation]): CircuitState = {

    val sinkToPlusArgsAnnoMap = annos.map(anno => anno.target -> anno).toMap
    val bridgeTopWiringAnnos = annos.map(anno => BridgeTopWiringAnnotation(anno.target, anno.clock))

    // Step 1: Call BridgeTopWiring
    val topWiringPrefix = "plusargs"
    val wiredState = (new BridgeTopWiring(topWiringPrefix + "_")).execute(
      state.copy(annotations = state.annotations ++ bridgeTopWiringAnnos))
    val outputAnnos = wiredState.annotations.collect({ case a: BridgeTopWiringOutputAnnotation => a })
    val sortedOutputAnnos = outputAnnos.sortBy(_.srcClockPort.ref)

    // Step 2: For each group of wired events, generate associated bridge annotations
    val c = wiredState.circuit
    val topModule = c.modules.collectFirst({ case m: Module if m.name == c.main => m }).get
    val topMT = ModuleTarget(c.main, c.main)
    val topNS = Namespace(topModule)
    val addedPorts = mutable.ArrayBuffer[Port]()
    val addedStmts = mutable.ArrayBuffer[Statement]()
    // Needed to pass out the widths for each plusarg; sufficient to grab just uint ports
    val portWidthMap = topModule.ports.collect {
      case Port(_, name, _, UIntType(IntWidth(w))) => name -> w.toInt
    }.toMap

    val bridgeAnnos = for ((srcClockRT, oAnno) <- sortedOutputAnnos.map(a => (a.srcClockPort, a))) yield {
      val sinkClockRT = oAnno.sinkClockPort
      val fcca = FAMEChannelConnectionAnnotation.sink(
          oAnno.topSink.ref,
          WireChannel,
          Some(sinkClockRT),
          Seq(oAnno.topSink))

      val plusArgsAnno = sinkToPlusArgsAnnoMap(oAnno.pathlessSource)

      val bridgeAnno = BridgeIOAnnotation(
        target = topMT.ref(topWiringPrefix),
        // We need to pass the name of the trigger port so each bridge can
        // disambiguate between them and connect to the correct one in simulation mapping
        widgetClass = classOf[PlusArgsBridgeModule].getName,
        widgetConstructorKey = PlusArgsBridgeParams(plusArgsAnno.name, plusArgsAnno.default, plusArgsAnno.docstring, plusArgsAnno.width),
        channelNames = Seq(fcca).map(_.globalName)
      )
      Seq(bridgeAnno) :+ fcca
    }

    val updatedCircuit = c.copy(modules = c.modules.map({
      case m: Module if m.name == c.main => m.copy(ports = m.ports ++ addedPorts, body = Block(m.body, addedStmts.toSeq:_*))
      case o => o
    }))

    val cleanedAnnotations = wiredState.annotations.filterNot(outputAnnos.toSet)
    CircuitState(updatedCircuit, wiredState.form, cleanedAnnotations ++ bridgeAnnos.flatten)
  }

  def doTransform(state: CircuitState): CircuitState = {
    val plusArgsAnnos = state.annotations.collect {
      case a: PlusArgsFirrtlAnnotation => a
    }

    if (!plusArgsAnnos.isEmpty) {
      println(s"[PlusArgs] PlusArgs are:")
      plusArgsAnnos.foreach({ case anno => println(s"   Name: ${anno.name} Docstring: ${anno.docstring} DefaultValue: ${anno.default} Width: ${anno.width}") })

      implementViaBridge(state, plusArgsAnnos.toSeq)
    } else { state }
  }

  def execute(state: CircuitState): CircuitState = {
    val updatedState = doTransform(state)
    // Clean up annotations so that their ReferenceTargets, which
    // are implicitly marked as DontTouch, can be optimized across
    updatedState.copy(
      annotations = updatedState.annotations.filter {
        case PlusArgsFirrtlAnnotation(_,_,_,_,_,_,_) => false
        case o => true
      })
  }
}
