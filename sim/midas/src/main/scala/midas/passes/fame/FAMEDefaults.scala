// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import Mappers._
import ir._
import annotations._
import collection.mutable.{ArrayBuffer, LinkedHashSet}

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
    val globalSignals = state.annotations.collect({ case g: FAMEGlobalSignal => g.target.ref }).toSet
    val channelNames = state.annotations.collect({ case fca: FAMEChannelConnectionAnnotation => fca.globalName })
    val channelNS = Namespace(channelNames)
    def isGlobal(topPort: Port) = globalSignals.contains(topPort.name)
    def isBound(topPort: Port) = analysis.channelsByPort.contains(analysis.topTarget.ref(topPort.name))
    val defaultExtChannelAnnos = topModule.ports.filterNot(isGlobal).filterNot(isBound).flatMap({
      case Port(_, _, _, ClockType) => None // FIXME: Reject the clock in RC's debug interface
      case Port(_, name, Input, _)  => Some(FAMEChannelConnectionAnnotation(channelNS.newName(name), WireChannel, None, Some(Seq(analysis.topTarget.ref(name)))))
      case Port(_, name, Output, _) => Some(FAMEChannelConnectionAnnotation(channelNS.newName(name), WireChannel, Some(Seq(analysis.topTarget.ref(name))), None))
    })
    val channelModules = new LinkedHashSet[String] // TODO: find modules to absorb into channels, don't label as FAME models
    val defaultLoopbackAnnos = new ArrayBuffer[FAMEChannelConnectionAnnotation]
    val defaultModelAnnos = new ArrayBuffer[FAMETransformAnnotation]
    val topTarget = ModuleTarget(state.circuit.main, topModule.name)
    def onStmt(stmt: Statement): Statement = stmt.map(onStmt) match {
      case wi @ WDefInstance(_, iname, mname, _) if (!channelModules.contains(mname)) =>
        defaultModelAnnos += FAMETransformAnnotation(FAME1Transform, topTarget.copy(module = mname))
        wi
      case c @ Connect(_, WSubField(WRef(lhsiname, _, InstanceKind, _), lhspname, _, _), WSubField(WRef(rhsiname, _, InstanceKind, _), rhspname, _, _)) =>
        if (c.loc.tpe != ClockType && c.expr.tpe != ClockType) {
          defaultLoopbackAnnos += FAMEChannelConnectionAnnotation(
            channelNS.newName(s"${rhsiname}_${rhspname}__to__${lhsiname}_${lhspname}"),
            WireChannel,
            Some(Seq(topTarget.ref(rhsiname).field(rhspname))),
            Some(Seq(topTarget.ref(lhsiname).field(lhspname))))
        }
        c
      case s => s
    }
    topModule.body.map(onStmt)
    state.copy(annotations = state.annotations ++ defaultExtChannelAnnos ++ defaultLoopbackAnnos ++ defaultModelAnnos)
  }
}
