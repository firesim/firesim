// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import Mappers._
import ir._
import annotations._
import collection.mutable.{ArrayBuffer, LinkedHashSet}

import midas.targetutils.FAMEAnnotation
/**
  *  Assumes:
  *    - AQB form (target module hierachy matches eventually LI-BDN graph, with
  *      connectivity still in place.)
  *    - Only extant FCCAs are on top-level I/O
  *
  *  Run after ExtractModel
  *  Label all unbound top-level ports as wire channels
  *  Label *all* model-to-model connections as wire channels
  *  Label all children of the top model to be FAME1 transformed
  */




class FAMEDefaults extends Transform {
  def inputForm = LowForm
  def outputForm = LowForm

  override def execute(state: CircuitState): CircuitState = {
    val analysis = new FAMEChannelAnalysis(state)
    val topModule = state.circuit.modules.find(_.name == state.circuit.main).get.asInstanceOf[Module]
    val fameAnnos = state.annotations.collect({ case fa: FAMEAnnotation => fa }) // for performance, avoid other annos
    val globalSignals = fameAnnos.collect({ case g: FAMEGlobalSignal => g.target.ref }).toSet
    val channelNames = fameAnnos.collect({ case fca: FAMEChannelConnectionAnnotation => fca.globalName })
    val channelNS = Namespace(channelNames)
    def isGlobal(topPort: Port) = globalSignals.contains(topPort.name)
    def isBound(topPort: Port) = analysis.channelsByPort.contains(analysis.topTarget.ref(topPort.name))
    val channelModules = new LinkedHashSet[String] // TODO: find modules to absorb into channels, don't label as FAME models
    val defaultLoopbackAnnos = new ArrayBuffer[FAMEChannelConnectionAnnotation]
    val defaultModelAnnos = new ArrayBuffer[FAMETransformAnnotation]
    val topTarget = ModuleTarget(state.circuit.main, topModule.name)
    def onStmt(stmt: Statement): Statement = stmt.map(onStmt) match {
      case wi @ WDefInstance(_, iname, mname, _) if (!channelModules.contains(mname)) =>
        defaultModelAnnos += FAMETransformAnnotation(topTarget.copy(module = mname))
        wi
      case c @ Connect(_, WSubField(WRef(lhsiname, _, InstanceKind, _), lhspname, _, _), WSubField(WRef(rhsiname, _, InstanceKind, _), rhspname, _, _)) =>
        if (c.loc.tpe != ClockType && c.expr.tpe != ClockType) {
          defaultLoopbackAnnos += FAMEChannelConnectionAnnotation.implicitlyClockedLoopback(
            channelNS.newName(s"${rhsiname}_${rhspname}__to__${lhsiname}_${lhspname}"),
            WireChannel,
            Seq(topTarget.ref(rhsiname).field(rhspname)),
            Seq(topTarget.ref(lhsiname).field(lhspname)))
        }
        c
      case s => s
    }
    topModule.body.map(onStmt)
    state.copy(annotations = state.annotations ++  defaultLoopbackAnnos ++ defaultModelAnnos)
  }
}
