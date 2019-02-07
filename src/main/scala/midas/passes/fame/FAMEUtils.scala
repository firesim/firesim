// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import ir._
import Utils._
import Mappers._
import transforms.CheckCombLoops
import annotations._
import graph.DiGraph
import scala.collection
import collection.mutable
import collection.mutable.{LinkedHashSet, LinkedHashMap, MultiMap}

object RTRenamer {
  // TODO: determine order for multiple renames, or just check of == 1 rename?
  def exact(renames: RenameMap): (ReferenceTarget => ReferenceTarget) = {
    { rt =>
      val renameMatches = renames.get(rt).getOrElse(Seq(rt)).collect({ case rt: ReferenceTarget => rt })
      assert(renameMatches.length == 1)
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

private [fame] object HostReset {
  def makePort(ns: Namespace): Port =
    new Port(NoInfo, ns.newName("hostReset"), Input, Utils.BoolType)
}

private[fame] class FAMEChannelAnalysis(val state: CircuitState, val fameType: FAMETransformType) {
  // TODO: only transform submodules of model modules
  // TODO: add renames!
  val circuit = state.circuit
  val syncModules = circuit.modules.filter(_.ports.exists(_.tpe == ClockType)).map(m => ModuleTarget(circuit.main, m.name)).toSet
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

  lazy val connectivity = (new CheckCombLoops).analyze(state)

  val channels = new LinkedHashSet[String]
  val channelsByPort = new LinkedHashMap[ReferenceTarget, String]
  val transformedModules = new LinkedHashSet[ModuleTarget]
  state.annotations.collect({
    case fta @ FAMETransformAnnotation(tpe, mt) if (tpe == fameType) =>
      transformedModules += mt
    case fca: FAMEChannelConnectionAnnotation =>
      channels += fca.globalName
      fca.sinks.toSeq.flatten.foreach({ rt => channelsByPort(rt) = fca.globalName })
      fca.sources.toSeq.flatten.foreach({ rt => channelsByPort(rt) = fca.globalName })
  })

  val topTarget = ModuleTarget(circuit.main, circuit.main)
  private val moduleOfInstance = new LinkedHashMap[String, String]
  val topConnects = new LinkedHashMap[ReferenceTarget, ReferenceTarget]
  val inputChannels = new LinkedHashMap[ModuleTarget, mutable.Set[String]] with MultiMap[ModuleTarget, String]
  val outputChannels = new LinkedHashMap[ModuleTarget, mutable.Set[String]] with MultiMap[ModuleTarget, String]
  moduleNodes(topTarget).asInstanceOf[Module].body.map(getTopConnects)
  def getTopConnects(stmt: Statement): Statement = stmt.map(getTopConnects) match {
    case WDefInstance(_, iname, mname, _) =>
      moduleOfInstance(iname) = mname
      EmptyStmt
    case Connect(_, WRef(tpname, _, _, _), WSubField(WRef(iname, _, _, _), pname, _, _)) =>
      val tpRef = topTarget.ref(tpname)
      channelsByPort.get(tpRef).foreach({ cname =>
        val child = topTarget.instOf(iname, moduleOfInstance(iname))
        topConnects(tpRef) = child.ref(pname)
        outputChannels.addBinding(child.ofModuleTarget, cname)
      })
      EmptyStmt
    case Connect(_, WSubField(WRef(iname, _, _, _), pname, _, _), WRef(tpname, _, _, _)) =>
      val tpRef = topTarget.ref(tpname)
      channelsByPort.get(tpRef).foreach({ cname =>
        val child = topTarget.instOf(iname, moduleOfInstance(iname))
        topConnects(tpRef) = child.ref(pname)
        inputChannels.addBinding(child.ofModuleTarget, cname)
      })
      EmptyStmt
    case s => EmptyStmt
  }

  val transformedSinks = new LinkedHashSet[String]
  val transformedSources = new LinkedHashSet[String]
  val sinkModel = new LinkedHashMap[String, InstanceTarget]
  val sourceModel = new LinkedHashMap[String, InstanceTarget]
  val sinkPorts = new LinkedHashMap[String, Seq[ReferenceTarget]]
  val sourcePorts = new LinkedHashMap[String, Seq[ReferenceTarget]]
  val staleTopPorts = new LinkedHashSet[ReferenceTarget]
  state.annotations.collect({
    case fca: FAMEChannelConnectionAnnotation =>
      channels += fca.globalName
      val sinks = fca.sinks.toSeq.flatten
      sinkPorts(fca.globalName) = sinks
      sinks.headOption.filter(rt => transformedModules.contains(ModuleTarget(rt.circuit, topConnects(rt).encapsulatingModule))).foreach({ rt =>
        assert(!topConnects(rt).isLocal) // need instance info
        sinkModel(fca.globalName) = topConnects(rt).targetParent.asInstanceOf[InstanceTarget]
        transformedSinks += fca.globalName
        staleTopPorts ++= sinks
      })
      val sources = fca.sources.toSeq.flatten
      sourcePorts(fca.globalName) = sources
      sources.headOption.filter(rt => transformedModules.contains(ModuleTarget(rt.circuit, topConnects(rt).encapsulatingModule))).foreach({ rt =>
        assert(!topConnects(rt).isLocal) // need instance info
        sourceModel(fca.globalName) = topConnects(rt).targetParent.asInstanceOf[InstanceTarget]
        transformedSources += fca.globalName
        staleTopPorts ++= sources
      })
  })

  val hostReset = state.annotations.collect({ case FAMEHostReset(rt) => rt }).head

  def inputPortsByChannel(m: DefModule): Map[String, Seq[Port]] = {
    val iChannels = inputChannels.get(ModuleTarget(circuit.main, m.name)).toSet.flatten
    iChannels.map({
      cname => (cname, sinkPorts(cname).map(topConnects(_).pathlessTarget).map(portNodes(_)))
    }).toMap
  }

  def outputPortsByChannel(m: DefModule): Map[String, Seq[Port]] = {
    val oChannels = outputChannels.get(ModuleTarget(circuit.main, m.name)).toSet.flatten
    oChannels.map({
      cname => (cname, sourcePorts(cname).map(topConnects(_).pathlessTarget).map(portNodes(_)))
    }).toMap
  }

  def getSinkHostDecoupledChannelType(cName: String): Type = {
    FAMEChannelAnalysis.getHostDecoupledChannelType(cName, sinkPorts(cName).map(portNodes(_)))
  }

  def getSourceHostDecoupledChannelType(cName: String): Type = {
    FAMEChannelAnalysis.getHostDecoupledChannelType(cName, sourcePorts(cName).map(portNodes(_)))
  }

}


