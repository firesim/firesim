// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import Mappers._
import ir._
import annotations._
import collection.mutable.{ArrayBuffer, LinkedHashSet, LinkedHashMap}

import midas.targetutils.FAMEAnnotation

// Assumes: AQB form
// Run after ExtractModel
// Label all unbound top-level ports as wire channels
// Label *all* model-to-model connections as wire channels
// Label all children of the top model to be FAME1 transformed

class FAMEDefaults extends Transform {
  def inputForm = LowForm
  def outputForm = LowForm

  override def execute(state: CircuitState): CircuitState = {
    val analysis = new FAMEChannelAnalysis(state, FAME1Transform)
    val topModule = state.circuit.modules.find(_.name == state.circuit.main).get.asInstanceOf[Module]
    val fameAnnos = state.annotations.collect({ case fa: FAMEAnnotation => fa }) // for performance, avoid other annos
    val globalSignals = fameAnnos.collect({ case g: FAMEGlobalSignal => g.target.ref }).toSet
    val channelNames = fameAnnos.collect({ case fca: FAMEChannelConnectionAnnotation => fca.globalName })
    val channelNS = Namespace(channelNames)

    def isGlobal(topPort: Port) = globalSignals.contains(topPort.name)
    def isBound(topPort: Port) = analysis.channelsByPort.contains(analysis.topTarget.ref(topPort.name))

    val channelModules = new LinkedHashSet[String] // TODO: find modules to absorb into channels, don't label as FAME models
    val basicLoopbackConns = new LinkedHashMap[(String, String), ArrayBuffer[(String, String)]]
    val defaultModelAnnos = new ArrayBuffer[FAMETransformAnnotation]
    val topTarget = ModuleTarget(state.circuit.main, topModule.name)

    def onStmt(stmt: Statement): Statement = stmt.map(onStmt) match {
      case wi @ WDefInstance(_, iname, mname, _) if (!channelModules.contains(mname)) =>
        defaultModelAnnos += FAMETransformAnnotation(FAME1Transform, topTarget.copy(module = mname))
        wi
      case c @ Connect(_, WSubField(WRef(lhsiname, _, InstanceKind, _), lhspname, _, _), WSubField(WRef(rhsiname, _, InstanceKind, _), rhspname, _, _)) =>
        if (c.loc.tpe != ClockType && c.expr.tpe != ClockType) {
	  println(s"LOOPBACK: ${c.loc}")
          basicLoopbackConns.getOrElseUpdate((lhsiname, rhsiname), new ArrayBuffer[(String, String)]) += new Tuple2(lhspname, rhspname)
        }
        c
      case s => s
    }

    topModule.body.map(onStmt)
    val defaultLoopbackAnnos = basicLoopbackConns.map {
      case ((lhsi, rhsi), v) =>
        val (sinks, sources) = v.unzip
	val sinkRTs = sinks.map(p => topTarget.ref(lhsi).field(p))
	val sourceRTs = sources.map(p => topTarget.ref(rhsi).field(p))
	val name = channelNS.newName(s"${rhsi}__to__${lhsi}")
	FAMEChannelConnectionAnnotation.implicitlyClockedLoopback(name, WireChannel, sourceRTs, sinkRTs)
    }

    state.copy(annotations = state.annotations ++ defaultLoopbackAnnos ++ defaultModelAnnos)
  }
}
