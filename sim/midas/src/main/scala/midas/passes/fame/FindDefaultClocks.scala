// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import firrtl.traversals.Foreachers._
import firrtl.ir._
import firrtl.annotations._
import firrtl.graph.{MutableDiGraph, DiGraph}

import collection.mutable

/** In general, multi-clock Golden Gate simulations contain exactly one "hub" model that coordinates the clock domains
  * and has a clock channel. All other non-hub models have a single clock domain.
  *
  * Before this pass runs, channels (encoded by [[FAMEChannelConnectionAnnotation]]s)
  * that run between models have no associated clock. The FAME transform
  * expects that all channels that connect to the hub model have an associated
  * clock. This pass builds a graph of clock connectivity between models and
  * uses that to populate the the clock field of inter-model
  * [[FAMEChannelConnectionAnnotations]] that are sourced or sinked by the hub
  * model.
  *
  * [[FAMEChannelConnectionAnnotation]]s between non-hub models are not changed.
  */

object FindDefaultClocks extends Transform {
  def inputForm = LowForm
  def outputForm = LowForm

  /**
    * Used to encode clock connectivity. The setting of the clockPort param defines two classes of node:
    * - clockPort = None: A non-hub model instance (these have a single clock),
    *                     so we need not track an additional port reference.
    * - clockPort = Some(RT): Sources and sinks on the hub model. Since the hub has multiple target clocks the
    *                         clockPort disambiguates the clock of the hub
    */
  case class ClockConnNode(name: String, clockPort: Option[ReferenceTarget] = None)
  type ClockConnMap = DiGraph[ClockConnNode]

  /**
    * By looking at the top-level connects on ClockTypes, build a graph of
    * connectivity that will allow us to tie cliques of models back to a source
    * port on the hub.
    */
  def recordClockConnection(
      topTarget: ModuleTarget,
      defaultClocks: MutableDiGraph[ClockConnNode],
      hubModel: String,
      )(stmt: Statement): Unit = stmt match {
    case Connect(_, WSubField(WRef(lInst, _, InstanceKind, _), lPort, ClockType, _), WSubField(WRef(rInst, _, InstanceKind, _), rPort, ClockType, _)) =>
        defaultClocks.addPairWithEdge(
          ClockConnNode(lInst, if (lInst == hubModel) Some(topTarget.ref(lInst).field(lPort)) else None),
          ClockConnNode(rInst, if (rInst == hubModel) Some(topTarget.ref(rInst).field(rPort)) else None))
    case s => s.foreach(recordClockConnection(topTarget, defaultClocks, hubModel))
  }

  // Finds a driver (a source port on the hub model) for a given model instance
  def getDefaultClock(clockGraph: ClockConnMap, satModelInstName: String): ReferenceTarget = {
    clockGraph.reachableFrom(ClockConnNode(satModelInstName)).collectFirst {
      case ClockConnNode(_, Some(port)) => port
    }.get
  }


  def addDefaultClocks(hubName: String, clockGraph: ClockConnMap)(anno: Annotation): Annotation = anno match {
    case fcca @ FAMEChannelConnectionAnnotation(name, info, None, Some(sources), Some(sinks)) =>
      // unclocked loopback channel
      val sourceModelInst = sources.head.ref
      val sinkModelInst = sinks.head.ref
      if (sinkModelInst != hubName) {
        fcca.copy(clock = Some(getDefaultClock(clockGraph, sinkModelInst)))
      } else if (sourceModelInst != hubName) {
        fcca.copy(clock = Some(getDefaultClock(clockGraph, sourceModelInst)))
      } else {
        fcca
      }
    case a => a
  }

  // Hub self-loops can be introduced as a result of extracting passthroughs in
  // satellite models.  For these channels, a default clock cannot be inferred by
  // looking at Connect statements.  Instead find a second channel that shares the
  // same source on the hub, and copy it's clock (this second channel must go to a
  // satellite model, and thus has a resolvable default clock).
  def resolveClocksForHubSelfLoops(annos: Seq[Annotation]): Seq[Annotation] = {
    val clockMap = mutable.HashMap[Seq[ReferenceTarget], ReferenceTarget]()
    annos.collect {
      case FAMEChannelConnectionAnnotation(name, info, Some(clock), Some(srcs), _) =>
        clockMap(srcs) = clock
    }
    annos map {
      case fcca @ FAMEChannelConnectionAnnotation(_, _, None, Some(srcs), Some(_)) =>
        fcca.copy(clock = Some(clockMap(srcs)))
      case o => o
    }
  }

  def getStmts(stmt: Statement): Seq[Statement] = stmt match {
    case Block(stmts) => stmts.flatMap(getStmts(_))
    case s => Seq(s)
  }

  def execute(state: CircuitState): CircuitState = {
    val topModule = state.circuit.modules.find(_.name == state.circuit.main).get.asInstanceOf[Module]
    val cTarget = CircuitTarget(state.circuit.main)
    val topTarget = cTarget.module(topModule.name)
    val defaultClocks = new MutableDiGraph[ClockConnNode]

    // Find an arbitrary clock channel sink
    val refClock = state.annotations.collectFirst {
      case FAMEChannelConnectionAnnotation(_, TargetClockChannel(_,_), None, _, Some(sinks)) => sinks.head
    }

    // Get the wrapper top port reference it points to
    val refClockPort = refClock.get.ref

    val hubModel = getStmts(topModule.body).collectFirst {
      case Connect(_, WSubField(WRef(lInst, _, _, _), _, _, _), WRef(`refClockPort`, _, PortKind, _)) => lInst
    }.get

    topModule.body.foreach(recordClockConnection(topTarget, defaultClocks, hubModel))
    val clockGraph = DiGraph(defaultClocks)
    // First Update all FCCAs that are not self-loops on the hub model
    val updatedAnnos = state.annotations.map(addDefaultClocks(hubModel, clockGraph))
    // Provide clocks for remaining annotations by looking at FCCAs with a common source
    state.copy(annotations = resolveClocksForHubSelfLoops(updatedAnnos))
  }
}
