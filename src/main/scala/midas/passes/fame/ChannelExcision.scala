// See LICENSE for license details.

package midas.passes.fame

import java.io.{PrintWriter, File}

import firrtl._
import ir._
import Mappers._
import Utils._
import firrtl.passes.MemPortUtils
import annotations.{ModuleTarget, ReferenceTarget, Annotation, SingleTargetAnnotation}

import scala.collection.mutable

class ChannelExcision extends Transform {
  def inputForm = LowForm
  def outputForm = LowForm

  val addedChannelAnnos = new mutable.ArrayBuffer[FAMEModelAnnotation]()
  val pipeChannels = new mutable.HashMap[(ReferenceTarget, ReferenceTarget), String]()


  override def execute(state: CircuitState): CircuitState = {
    val renames = RenameMap()
    val topModule = state.circuit.modules.find(_.name == state.circuit.main).get.asInstanceOf[Module]
    val topTarget = ModuleTarget(state.circuit.main, topModule.name)
    def subfieldTarget(instance: String, field: String) = topTarget.ref(instance).field(field)
    def portTarget(p: Port) = topTarget.ref(p.name)

    def onStmt(addedPorts: mutable.ArrayBuffer[Port])(s: Statement): Statement = s.map(onStmt(addedPorts)) match {
      case c @ Connect(_, lhs @ WSubField(WRef(lhsiname, _, InstanceKind, _), lhspname, _, _),
                          rhs @ WSubField(WRef(rhsiname, _, InstanceKind, _), rhspname, _, _)) =>
        val lhsTarget = subfieldTarget(lhsiname, lhspname)
        val rhsTarget = subfieldTarget(rhsiname, rhspname)
        pipeChannels.get((lhsTarget, rhsTarget)) match {
          case Some(chName) =>
            val srcP = Port(NoInfo, s"${rhsiname}_${rhspname}_source", Output, lhs.tpe)
            val sinkP = Port(NoInfo, s"${lhsiname}_${lhspname}_sink", Input, rhs.tpe)
            addedPorts ++= Seq(srcP, sinkP)
            renames.record(lhsTarget, portTarget(sinkP))
            renames.record(rhsTarget, portTarget(srcP))
            Block(Seq(Connect(NoInfo, lhs, WRef(sinkP)), Connect(NoInfo, WRef(srcP), rhs)))
          case None => c
        }
      case s => s
    }

    def onModule(m: DefModule): DefModule = m match {
      case mod @ Module(_,name,_,_) if name == state.circuit.main =>
        val addedPorts = new mutable.ArrayBuffer[Port]()
        mod.copy(body = mod.body.map(onStmt(addedPorts)))
           .copy(ports = mod.ports ++ addedPorts)
      case _ => m
    }

    // Step 1: Analysis -> build a map from reference targets to channel name
    state.annotations.collect({
      case fta@ FAMEChannelConnectionAnnotation(name, PipeChannel(_), Some(srcs), Some(sinks)) =>
      sinks.zip(srcs).foreach({ pipeChannels(_) = name })
    })

    // Step 2: Generate new ports, find and replace connections
    val circuit = state.circuit.map(onModule)

    state.copy(circuit = circuit, renames = Some(renames))
  }
}
