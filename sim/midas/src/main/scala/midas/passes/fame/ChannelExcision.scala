// See LICENSE for license details.

package midas.passes.fame


import firrtl._
import ir._
import Mappers._
import annotations.{ModuleTarget, ReferenceTarget}

import scala.collection.mutable

class ChannelExcision extends Transform {
  def inputForm = LowForm
  def outputForm = LowForm

  // These have both their sources and sinks defined
  val model2modelPipeChannels = new mutable.HashMap[(ReferenceTarget, ReferenceTarget), String]()
  // These have only their sinks defined, and can fanout to multiple models
  val bridgeSourcedPipeChannels = new mutable.HashMap[ReferenceTarget, String]()
  // Track bridge-sourced channel ports that have been duplicated; used to generate new FCCAs.
  val sourcePortDups = new mutable.LinkedHashMap[ReferenceTarget, mutable.Set[ReferenceTarget]] with mutable.MultiMap[ReferenceTarget, ReferenceTarget] {
    // To preserve per-key insertion orer
    override def makeSet: mutable.Set[ReferenceTarget] = new mutable.LinkedHashSet[ReferenceTarget]
  }

  override def execute(state: CircuitState): CircuitState = {
    val renames = RenameMap()
    val topModule = state.circuit.modules.find(_.name == state.circuit.main).get.asInstanceOf[Module]
    val topTarget = ModuleTarget(state.circuit.main, topModule.name)
    def subfieldTarget(instance: String, field: String) = topTarget.ref(instance).field(field)
    def portTarget(p: Port) = topTarget.ref(p.name)

    val srcPorts = new mutable.HashSet[Port]()
    def onStmt(addedPorts: mutable.ArrayBuffer[Port], ns: Namespace)
        (s: Statement): Statement = s.map(onStmt(addedPorts, ns)) match {
      case c @ Connect(_, lhs @ WSubField(WRef(lhsiname, _, InstanceKind, _), lhspname, lType, _),
                          rhs @ WSubField(WRef(rhsiname, _, InstanceKind, _), rhspname, rType, _)) =>
        val lhsTarget = subfieldTarget(lhsiname, lhspname)
        val rhsTarget = subfieldTarget(rhsiname, rhspname)
        if (model2modelPipeChannels.contains((lhsTarget, rhsTarget)) || lType == ClockType) {
          // Channels can fanout from one model to many. Generate a single source port, but many
          // sink ports. The forking is handled in simulation wrapper during channel generation

          val srcP = Port(NoInfo, s"${rhsiname}_${rhspname}_source", Output, lType)
          val srcAssign = if (!srcPorts(srcP)) {
            addedPorts += srcP
            srcPorts += srcP
            renames.record(rhsTarget, portTarget(srcP))
          }

          val sinkP = Port(NoInfo, ns.newName(s"${lhsiname}_${lhspname}_sink"), Input, rType)
          addedPorts += sinkP
          renames.record(lhsTarget, portTarget(sinkP))
          Block(Seq(Connect(NoInfo, lhs, WRef(sinkP)), Connect(NoInfo, WRef(srcP), rhs)))
        } else {
          c
        }
      // Potential fanouts from a bridge-source to multiple models. Excise the
      // channel by duplicating the input port
      case c @ Connect(_, lhs @ WSubField(WRef(lhsiname, _, InstanceKind, _), lhspname, lType, _),
                          rhs @ WRef(rhspname, rType, PortKind, _)) =>
        val rhsTarget = topTarget.ref(rhspname)
        if (bridgeSourcedPipeChannels.contains(rhsTarget)) {
          sourcePortDups.get(rhsTarget) match {
            // Reuse the existing port for the first connection from it
            case None =>
              sourcePortDups.addBinding(rhsTarget, rhsTarget)
              c
            // Duplicate it otherwise
            case Some(targets) =>
              val pName = ns.newName(s"${rhspname}_${targets.size}")
              val srcP = Port(NoInfo, pName, Input, rType)
              addedPorts += srcP
              sourcePortDups.addBinding(rhsTarget, topTarget.ref(pName))
              Connect(NoInfo, lhs, WRef(srcP))
            }
        } else {
          c
        }
      // Fanout paths from a bridge source to a bridge sink.
      case c @ Connect(_, lhs @ WRef(_, _, PortKind, _),
                          rhs @ WRef(rhspname, rType, PortKind, _)) =>
        val rhsTarget = topTarget.ref(rhspname)
        if (bridgeSourcedPipeChannels.contains(rhsTarget)) {
          sourcePortDups.get(rhsTarget) match {
            // Reuse the existing port for the first connection from it
            case None =>
              sourcePortDups.addBinding(rhsTarget, rhsTarget)
              c
            // Duplicate it otherwise
            case Some(targets) =>
              val pName = ns.newName(s"${rhspname}_${targets.size}")
              val srcP = Port(NoInfo, pName, Input, rType)
              addedPorts += srcP
              sourcePortDups.addBinding(rhsTarget, topTarget.ref(pName))
              Connect(NoInfo, lhs, WRef(srcP))
            }
        } else {
          c
        }
      case s => s
    }

    def onModule(m: DefModule): DefModule = m match {
      case mod @ Module(_,name,_,_) if name == state.circuit.main =>
        val addedPorts = new mutable.ArrayBuffer[Port]()
        val ns = Namespace(mod)
        mod.copy(body = mod.body.map(onStmt(addedPorts, ns)))
           .copy(ports = mod.ports ++ addedPorts)
      case _ => m
    }

    val modelSourcedFanouts = new mutable.LinkedHashMap[Seq[ReferenceTarget], mutable.Set[String]] with mutable.MultiMap[Seq[ReferenceTarget], String]
    val bridgeSourcedFCCAs = new mutable.ArrayBuffer[FAMEChannelConnectionAnnotation]()

    // Step 1: Analysis -> build a map from reference targets to channel name
    state.annotations.collect {
      case fta@ FAMEChannelConnectionAnnotation(name, PipeChannel(_), _, Some(srcs), Some(sinks)) =>
        modelSourcedFanouts.addBinding(srcs, name)
          sinks.zip(srcs).foreach { model2modelPipeChannels(_) = name }
      case fta@ FAMEChannelConnectionAnnotation(name, PipeChannel(_), _, None, Some(sinks)) =>
        sinks.foreach({ bridgeSourcedPipeChannels(_) = name })
        bridgeSourcedFCCAs += fta
    }

    // Step 2: Generate new ports, find and replace connections
    val circuit = state.circuit.map(onModule)

    // Step 3: Duplicate FCCAs for bridge-driven sources that fan out
    val bridgeSourceAnnos = (for (fcca <- bridgeSourcedFCCAs) yield {
      val addedFCCAs = for (idx <- 1 until sourcePortDups(fcca.sinks.get.head).size) yield {
        fcca.copy(
          globalName = s"${fcca.globalName}_${idx}",
          sinks = Some(fcca.sinks.get.map(rT => (sourcePortDups(rT).toSeq)(idx))))
      }
      FAMEChannelFanoutAnnotation((fcca +: addedFCCAs).map(_.globalName)) +: addedFCCAs
    }).flatten

    state.copy(circuit = circuit, annotations = state.annotations ++ bridgeSourceAnnos, renames = Some(renames))
  }
}
