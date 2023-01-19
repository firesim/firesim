//See LICENSE for license details.

package midas.passes

import midas.targetutils.{TriggerSourceAnnotation, TriggerSinkAnnotation}
import midas.passes.fame._

import freechips.rocketchip.util.DensePrefixSum
import firrtl._
import firrtl.annotations._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.passes.wiring.{SinkAnnotation, SourceAnnotation, WiringTransform}
import firrtl.Utils.BoolType

import scala.collection.mutable

/*
 * Implements Golden Gate's trigger system by emitting target-hardware to aggregate
 * all trigger source signals into trigger enables.
 *
 * Refer to the FireSim Docs for more detail, and for a schematic of the generated HW.
 */
private[passes] object TriggerWiring extends firrtl.Transform {
  def inputForm = LowForm
  def outputForm = HighForm
  override def name = "[Golden Gate] Trigger Wiring"
  val topWiringPrefix = "simulationTrigger_"
  val sinkWiringKey = "trigger_sink"

  // Defines the width of credit and debit counters local to a specific clock domain 
  // For the trigger to function correctly:
  // localWidth >= log2Ceil(max(localCreditSources, localDebitSources) * Ceil(N/M))
  // where:
  //   local{Credit,Debit}Sources are the number of the associated source in the local clock domain,
  //   the local clock frequency is (N/M) times the base frequency
  val localCType = UIntType(IntWidth(16))
  // Defines the type of the global counter in the base clock domain.  This
  // just needs to be large enough to represent the largest number of credits
  // the system will produce. Since there are only two of these, make it large
  // to be safe.
  val globalCType = UIntType(IntWidth(32))

  // Masks off trigger sources when the are under reset.
  private def gateEventsWithReset(sourceModuleMap: Map[String, Seq[TriggerSourceAnnotation]],
                                  updatedAnnos: mutable.ArrayBuffer[TriggerSourceAnnotation])
                                 (mod: DefModule): DefModule = mod match {
    case m: Module if sourceModuleMap.isDefinedAt(m.name) =>
      val annos = sourceModuleMap(m.name)
      val mT = annos.head.enclosingModuleTarget()
      val moduleNS = Namespace(mod)
      val addedStmts = annos.flatMap({ anno =>
        if (anno.reset.nonEmpty) {
          val eventName = moduleNS.newName(anno.target.ref + "_masked")
          updatedAnnos += anno.copy(target = mT.ref(eventName))
          Seq(DefNode(NoInfo, eventName, And(Negate(WRef(anno.reset.get.ref)), WRef(anno.target.ref))))
        } else {
          updatedAnnos += anno
          Nil
        }
      })
      m.copy(body = Block(m.body, addedStmts:_*))
      case o => o
  }

  // Generates the sink-side hardware. See onStmtSink
  private def onModuleSink(sinkAnnoModuleMap: Map[String, Seq[TriggerSinkAnnotation]],
                   addedAnnos: mutable.ArrayBuffer[Annotation])
                  (m: DefModule): DefModule = m match {
    case m: Module if sinkAnnoModuleMap.isDefinedAt(m.name) =>
      val sinkNameMap = sinkAnnoModuleMap(m.name).map(anno => anno.target.ref -> anno).toMap
      val ns = Namespace(m)
      m.map(onStmtSink(sinkNameMap, addedAnnos, ns))
    case o => o
  }

  /**
    * For each TriggerSink:
    * 1) Emit a register that will synchronize the trigger signal to the to local domain (from the base one)
    * 2) Emit a wiring annotation pointing at that register.
    */
  private def onStmtSink(sinkAnnos: Map[String, TriggerSinkAnnotation],
                 addedAnnos: mutable.ArrayBuffer[Annotation],
                 ns: Namespace)
                (s: Statement): Statement = s.map(onStmtSink(sinkAnnos, addedAnnos, ns)) match {
    case node@DefNode(_,name,_) if sinkAnnos.isDefinedAt(name) =>
      val sinkAnno = sinkAnnos(name)
      val mT = sinkAnno.enclosingModuleTarget()
      val triggerSyncName = ns.newName("trigger_sync")
      val triggerSync = RegZeroPreset(NoInfo, triggerSyncName, BoolType, WRef(sinkAnno.clock.ref))
      addedAnnos += SinkAnnotation(mT.ref(triggerSyncName).toNamed, sinkWiringKey)
      Block(triggerSync, node.copy(value = WRef(triggerSync)))
    // Implement the missing cases?
    case r@DefRegister(_,name,_,_,_,_) if sinkAnnos.isDefinedAt(name) => ???
    case s => s
  }

  def execute(state: CircuitState): CircuitState = {

    val topModName = state.circuit.main
    val topMod = state.circuit.modules.find(_.name == topModName).get
    val prexistingPorts = topMod.ports
    // 1) Collect Trigger Annotations, and generate BridgeTopWiring annotations
    val srcCreditAnnos = new mutable.ArrayBuffer[TriggerSourceAnnotation]()
    val srcDebitAnnos  = new mutable.ArrayBuffer[TriggerSourceAnnotation]()
    val sinkAnnos      = new mutable.ArrayBuffer[TriggerSinkAnnotation]()
    state.annotations.collect({
      case a: TriggerSourceAnnotation if a.sourceType => srcCreditAnnos += a
      case a: TriggerSourceAnnotation                 => srcDebitAnnos += a
      case a: TriggerSinkAnnotation                   => sinkAnnos += a
    })

    require(!(srcCreditAnnos.isEmpty && srcDebitAnnos.nonEmpty), "Provided trigger debit sources but no credit sources")
    // It may make sense to relax this in the future
    // This would enable trigger without posibility of disabling it in the future
    require(!(srcDebitAnnos.isEmpty && srcCreditAnnos.nonEmpty), "Provided trigger credit sources but no debit sources")

    val updatedState = if ((srcCreditAnnos.isEmpty && srcDebitAnnos.isEmpty) || sinkAnnos.isEmpty) {
      state
    } else {
      // Step 1) Gate credits and debits with their associated reset, if provided
      val updatedAnnos = new mutable.ArrayBuffer[TriggerSourceAnnotation]()
      val srcAnnoMap = (srcCreditAnnos ++ srcDebitAnnos).groupBy(_.enclosingModule()).map { case (k, v) => k -> v.toSeq }
      val gatedCircuit = state.circuit.map(gateEventsWithReset(srcAnnoMap, updatedAnnos))
      val (gatedCredits, gatedDebits) = updatedAnnos.partition(_.sourceType)

      // Step 2) Use bridge topWiring to generate inter-module connectivity -- but drop the port list
      val bridgeTopWiringAnnos = updatedAnnos.map(anno => BridgeTopWiringAnnotation(anno.target, anno.clock))
      val wiredState = (new BridgeTopWiring(topWiringPrefix)).execute(state.copy(
        circuit = gatedCircuit, annotations = state.annotations ++ bridgeTopWiringAnnos))
      val wiredTopModule = wiredState.circuit.modules.collectFirst({
        case m@Module(_,name,_,_) if name == topModName => m
      }).get
      val otherModules = wiredState.circuit.modules.filter(_.name != topModName)
      val addedPorts = wiredTopModule.ports.filterNot(p => prexistingPorts.contains(p))

      // Step 3: Group top-wired outputs by their associated clock
      val outputAnnos = wiredState.annotations.collect({ case a: BridgeTopWiringOutputAnnotation => a })
      val groupedTriggers = outputAnnos.groupBy(_.srcClockPort)

      // Step 4: Convert port assignments to wire assignments
      val portName2WireMap = addedPorts.map(p => p.name -> DefWire(NoInfo, p.name, p.tpe)).toMap
      def updateAssignments(stmt: Statement): Statement = stmt.map(updateAssignments) match {
        case c@Connect(_, WRef(name,_,_,_), _) if portName2WireMap.isDefinedAt(name) =>
          val defWire = portName2WireMap(name)
          Block(defWire, c.copy(loc = WRef(defWire)))
        case o => o
      }

      val portRemovedBody = wiredTopModule.body.map(updateAssignments)

      // 5) Per-clock-domain: count local credits and debits
      val ns = Namespace(wiredTopModule)
      val addedStmts = new mutable.ArrayBuffer[Statement]()

      def addReduce(bools: Seq[WRef]): WRef = DensePrefixSum(bools)({ case (a, b) => 
        val name = ns.newTemp
        val node = DefNode(NoInfo, name, DoPrim(PrimOps.Add, Seq(a, b), Seq.empty, UnknownType))
        addedStmts += node
        WRef(node)
      }).last

      def counter(name: String, tpe: UIntType, clock: WRef, incr: WRef): (DefRegister, DefNode) = {
        val countName = ns.newName(name)
        val count = RegZeroPreset(NoInfo, countName, tpe, clock)
        val nextName = ns.newName(countName + "_next")
        val next = DefNode(NoInfo, nextName, DoPrim(PrimOps.Add, Seq(WRef(count), incr), Seq.empty, tpe))
        val countUpdate = Connect(NoInfo, WRef(count), WRef(next))
        addedStmts ++= Seq(count, next, countUpdate)
        (count, next)
      }

      // Add-reduces a set of UInt signals, before adding it to a running count,
      // returning a reference to value the counter will take on in the next cycle.
      def doAccounting(counterType: UIntType, clock: WRef)(name: String, bools: Seq[WRef]): WRef =
        WRef(counter(name, counterType, clock, addReduce(bools))._2)

      // In each clock domain, add up all of the credits and debits
      val (localCredits, localDebits) = (for ((clockRT, oAnnos) <- groupedTriggers) yield {
        val credits = oAnnos.collect {
          case a if gatedCredits.exists(_.target == a.pathlessSource) => WRef(portName2WireMap(a.topSink.ref))
        }
        val debits =  oAnnos.collect {
          case a if gatedDebits.exists(_.target == a.pathlessSource) => WRef(portName2WireMap(a.topSink.ref))
        }
        def doLocalAccounting = doAccounting(localCType, WRef(clockRT.ref)) _

        val domainName = clockRT.ref
        (doLocalAccounting(s"${domainName}_credits", credits), doLocalAccounting(s"${domainName}_debits", debits))
      }).unzip

      // Step 6) Synchronize and aggregate local counts into global counts in the base clock domain
      val refClockRT = wiredState.annotations.collectFirst({
        case FAMEChannelConnectionAnnotation(_,TargetClockChannel(_,_),_,_,Some(clock :: _)) => clock
      }).get

      // We only need to use a single register to synchronize a signal in GG, we use two here
      // to measure how much the local count has changed between base clock cycles (hence, Diff).
      def syncAndDiff(next: WRef): WRef = {
        val name = next.name
        val syncNameS1 = ns.newName(s"${name}_count_sync_s1")
        val syncS1 = RegZeroPreset(NoInfo, syncNameS1, localCType, WRef(refClockRT.ref))
        val syncNameS2 = ns.newName(s"${name}_count_sync_s2")
        val syncS2 = RegZeroPreset(NoInfo, syncNameS2, localCType, WRef(refClockRT.ref))
        val diffName = ns.newName(s"${name}_diff")
        val diffNode = DefNode(NoInfo, diffName, DoPrim(PrimOps.Sub, Seq(WRef(syncS1), WRef(syncS2)), Seq.empty, localCType))
        addedStmts ++= Seq(
          syncS1, syncS2, diffNode,
          Connect(NoInfo, WRef(syncS1), next),
          Connect(NoInfo, WRef(syncS2), WRef(syncS1))
        )
        WRef(diffNode)
      }
      val creditUpdates = localCredits.map(syncAndDiff).toSeq
      val debitUpdates = localDebits.map(syncAndDiff).toSeq

      // Add together the changes in local debits and credits, and apply them
      // to the global count
      def doGlobalAccounting = doAccounting(globalCType, WRef(refClockRT.ref)) _
      val totalCredit = doGlobalAccounting("totalCredits", creditUpdates)
      val totalDebit = doGlobalAccounting("totalDebits", debitUpdates)

      // Step 7) Generate the trigger enable, and prep all sinks for Wiring by
      // adding a synchronization register
      val triggerName = ns.newName("trigger_source")
      val triggerSource = DefNode(NoInfo, triggerName, Neq(totalCredit, totalDebit))
      val triggerSourceRT = ModuleTarget(topModName, topModName).ref(triggerName)
      addedStmts += triggerSource
      val topModWithTrigger = wiredTopModule.copy(ports = prexistingPorts, body = Block(portRemovedBody,  addedStmts.toSeq:_*))
      val updatedCircuit = wiredState.circuit.copy(modules = topModWithTrigger +: otherModules)

      // Step 8) Wire the generated trigger to all sinks using the WiringTranform
      val sinkModuleMap = sinkAnnos.groupBy(_.target.module).map { case (k, v) => k -> v.toSeq }
      val wiringAnnos = new mutable.ArrayBuffer[Annotation]
      wiringAnnos += SourceAnnotation(triggerSourceRT.toNamed, sinkWiringKey)
      val preSinkWiringCircuit = updatedCircuit.map(onModuleSink(sinkModuleMap, wiringAnnos))
      val preSinkWiringState = CircuitState(preSinkWiringCircuit, HighForm, wiredState.annotations ++ wiringAnnos)

      val sinkWiringTransforms = Seq(
        new ResolveAndCheck,
        new HighFirrtlToMiddleFirrtl,
        new WiringTransform,
        new ResolveAndCheck)
      sinkWiringTransforms.foldLeft(preSinkWiringState)((in, xform) => xform.runTransform(in))
    }

    val cleanedAnnos = updatedState.annotations.flatMap({
      case a: TriggerSourceAnnotation => None
      case a: TriggerSinkAnnotation => None
      case a: BridgeTopWiringOutputAnnotation => None
      case o => Some(o)
    })
    updatedState.copy(annotations = cleanedAnnos)
  }
}
