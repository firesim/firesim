// See LICENSE for license details.

package midas.passes

import firrtl._
import firrtl.analyses.InstanceGraph
import firrtl.annotations.{ModuleTarget, ReferenceTarget, TargetToken}
import firrtl.ir._
import firrtl.Mappers._
import fame._
import Utils._

import collection.mutable
import java.io.{File, FileWriter, StringWriter}

import midas.core.SimUtils._

private[passes] class ChannelizeTargetIO(io: Seq[(String, chisel3.Data)]) extends firrtl.Transform {

  override def name = "[MIDAS] ChannelizeTargetIO"
  def inputForm = LowForm
  def outputForm = LowForm

  def execute(state: CircuitState): CircuitState = {
    val circuit = state.circuit

    // From trivial channel excision
    val topName = state.circuit.main
    val topModule = state.circuit.modules.find(_.name == topName).collect({
      case m: Module => m
    }).get

    val topModulePortMap: Map[String, Port] = topModule.ports.map({p => p.name -> p}).toMap

    val (wireSinks, wireSources, rvSinks, rvSources) = parsePortsSeq(io, alsoFlattenRVPorts = false)

    // Helper functions to generate annotations and ReferenceTargets
    def portRefTarget(field: String) = ReferenceTarget(circuit.main, circuit.main, Nil, field, Nil)

    def wireSinkAnno(chName: String) =
      FAMEChannelConnectionAnnotation(chName, PipeChannel(1), None, Some(Seq(portRefTarget(chName))))
    def wireSourceAnno(chName: String) =
      FAMEChannelConnectionAnnotation(chName, PipeChannel(1), Some(Seq(portRefTarget(chName))), None)

    def decoupledRevSinkAnno(name: String, readyTarget: ReferenceTarget) =
      FAMEChannelConnectionAnnotation(prefixWith(name, "rev"), DecoupledReverseChannel, None, Some(Seq(readyTarget)))
    def decoupledRevSourceAnno(name: String, readyTarget: ReferenceTarget) =
      FAMEChannelConnectionAnnotation(prefixWith(name, "rev"), DecoupledReverseChannel, Some(Seq(readyTarget)), None)

    def decoupledFwdSinkAnno(chName: String,
                             validTarget: ReferenceTarget,
                             readyTarget: ReferenceTarget,
                             leaves: Seq[ReferenceTarget]): FAMEChannelConnectionAnnotation =  {

      val chInfo = DecoupledForwardChannel(
        validSink   = Some(validTarget),
        readySource = Some(readyTarget),
        validSource = None,
        readySink   = None)
      FAMEChannelConnectionAnnotation(prefixWith(chName, "fwd"), chInfo, None, Some(leaves))
    }

    def decoupledFwdSourceAnno(chName: String,
                               validTarget: ReferenceTarget,
                               readyTarget: ReferenceTarget,
                               leaves: Seq[ReferenceTarget]): FAMEChannelConnectionAnnotation =  {

      val chInfo = DecoupledForwardChannel(
        validSource = Some(validTarget),
        readySink   = Some(readyTarget),
        validSink   = None,
        readySource = None)
      FAMEChannelConnectionAnnotation(prefixWith(chName, "fwd"), chInfo, Some(leaves), None)
    }

    // Generate ReferenceTargets for the leaves in an RV payload
    def getRVLeaves(name: String, field: chisel3.Data): Seq[ReferenceTarget] = field match {
      case b: chisel3.Record =>
        b.elements.toSeq flatMap { case (n, e) => getRVLeaves(prefixWith(name, n), e) }
      case v: chisel3.Vec[_] =>
        v.zipWithIndex flatMap { case (e, i) =>   getRVLeaves(prefixWith(name, i), e) }
      case b: chisel3.Element => Seq(portRefTarget(name))
      case _ => throw new RuntimeException("Unexpected type in ready-valid payload")
    }

    // Generate valid, ready, and payload reference targets for a decoupled interface
    def getRVTargets(port: chisel3.Data, name: String): (ReferenceTarget, ReferenceTarget, Seq[ReferenceTarget]) = {
      val validTarget = portRefTarget(prefixWith(name, "valid"))
      val readyTarget = portRefTarget(prefixWith(name, "ready"))
      val payloadTargets = getRVLeaves(prefixWith(name, "bits"), port)
      (validTarget, readyTarget, Seq(validTarget) ++ payloadTargets)
    }

    def rvSinkAnnos(chTuple: RVChTuple): Seq[FAMEChannelConnectionAnnotation] = chTuple match {
      case (port, name) =>
      val (vT, rT, pTs) = getRVTargets(port.bits, name)
      Seq(decoupledFwdSinkAnno(name, vT, rT, pTs), decoupledRevSourceAnno(name, rT))
    }

    def rvSourceAnnos(chTuple: RVChTuple): Seq[FAMEChannelConnectionAnnotation] = chTuple match {
      case (port, name) =>
      val (vT, rT, pTs) = getRVTargets(port.bits, name)
      Seq(decoupledFwdSourceAnno(name, vT, rT, pTs), decoupledRevSinkAnno(name, rT))
    }

    val chAnnos =
      wireSinks  .map({ case (_, chName) => wireSinkAnno(chName) }) ++
      wireSources.map({ case (_, chName) => wireSourceAnno(chName) }) ++
      rvSinks    .flatMap(rvSinkAnnos) ++
      rvSources  .flatMap(rvSourceAnnos)

    val f1Anno = FAMETransformAnnotation(FAME1Transform, ModuleTarget(topName, topName))
    state.copy(annotations = state.annotations ++ Seq(f1Anno) ++ chAnnos)
  }
}
