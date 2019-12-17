// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import firrtl.traversals.Foreachers._
import firrtl.ir._
import firrtl.annotations._

import collection.mutable

/** In general, multi-clock Golden Gate simulations contain exactly one "hub" model that coordinates the clock domains
  * and has a clock channel. Before this pass runs, loopback channels (those that run from one model to another) have no
  * associated clock. The FAME transform expects that all channels that connect to the hub model have an associated
  * clock, so this pass finds the top-level clock connection that drives the single clock of each "satellite" (non-hub)
  * model and associates the clock output of the hub model driving this clock with all channels connecting the hub and
  * the satellite. Channels between satellite models are not changed.
  */
object FindDefaultClocks extends Transform {
  def inputForm = LowForm
  def outputForm = LowForm

  case class ModelInstance(name: String)
  case class ClockConnection(source: ReferenceTarget, sink: ReferenceTarget)
  type ClockConnMap = mutable.Map[ModelInstance, ClockConnection]

  def recordDefaultClocks(topTarget: ModuleTarget, defaultClocks: ClockConnMap)(stmt: Statement): Unit = stmt match {
    case Connect(_, WSubField(WRef(lInst, _, InstanceKind, _), lPort, ClockType, _), WSubField(WRef(rInst, _, InstanceKind, _), rPort, ClockType, _)) =>
      // LHS must be "satellite" model receiving clock from "hub" model on RHS
      defaultClocks(ModelInstance(lInst)) = ClockConnection(topTarget.ref(rInst).field(rPort), topTarget.ref(lInst).field(lPort))
    case s => s.foreach(recordDefaultClocks(topTarget, defaultClocks))
  }

  def addDefaultClocks(hubModel: ModelInstance, defaultClocks: ClockConnMap)(anno: Annotation): Annotation = anno match {
    case fcca @ FAMEChannelConnectionAnnotation(name, info, None, Some(sources), Some(sinks)) =>
      // unclocked loopback channel
      val sourceModelInst = ModelInstance(sources.head.ref)
      val sinkModelInst = ModelInstance(sinks.head.ref)
      if (sourceModelInst == hubModel) {
        fcca.copy(clock = Some(defaultClocks(sinkModelInst).source))
      } else if (sinkModelInst == hubModel) {
        fcca.copy(clock = Some(defaultClocks(sourceModelInst).source))
      } else {
        fcca
      }
    case a => a
  }

  def getStmts(stmt: Statement): Seq[Statement] = stmt match {
    case Block(stmts) => stmts.flatMap(getStmts(_))
    case s => Seq(s)
  }

  def execute(state: CircuitState): CircuitState = {
    val topModule = state.circuit.modules.find(_.name == state.circuit.main).get.asInstanceOf[Module]
    val cTarget = CircuitTarget(state.circuit.main)
    val topTarget = cTarget.module(topModule.name)
    val defaultClocks = new mutable.LinkedHashMap[ModelInstance, ClockConnection]

    // Find an arbitrary clock channel sink
    val refClock = state.annotations.collectFirst {
      case FAMEChannelConnectionAnnotation(_, TargetClockChannel, None, _, Some(sinks)) => sinks.head
    }

    // Get the wrapper top port reference it points to
    val refClockPort = refClock.get.ref

    val hubModel = getStmts(topModule.body).collectFirst {
      case Connect(_, WSubField(WRef(lInst, _, _, _), _, _, _), WRef(`refClockPort`, _, PortKind, _)) => ModelInstance(lInst)
    }

    topModule.body.foreach(recordDefaultClocks(topTarget, defaultClocks))
    state.copy(annotations = state.annotations.map(addDefaultClocks(hubModel.get, defaultClocks)))
  }
}
