// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import firrtl.analyses.InstanceGraph
import firrtl.annotations._
import firrtl.ir._
import firrtl.passes.{InferTypes, ResolveKinds, ResolveFlows, ExpandConnects}
import firrtl.options.Dependency
import firrtl.Mappers._

import scala.collection.mutable

object HubModelControlWiring extends Transform with DependencyAPIMigration {

  override def prerequisites = Seq(Dependency[FAMETransform])
  override def optionalPrerequisites = Seq.empty
  override def optionalPrerequisiteOf = Seq(Dependency[midas.passes.SimulationMapping])

  override def invalidates(a: Transform): Boolean = a match {
    case InferTypes | ResolveKinds | ResolveFlows | ExpandConnects => true
    case _ => false
  }

  def execute(state: CircuitState): CircuitState = {
    val controlAnnos = state.annotations.collect { case a: SimulationControlAnnotation => a }

    require(controlAnnos.size == 1, s"${this.getClass.getSimpleName} expects a single SimulationControlAnnotation. Got: ${controlAnnos.size}")
    val c = state.circuit
    val controlAnno = controlAnnos.head

    val hubModel = c.modules.find(_.name == controlAnnos.head.enclosingModuleName).get

    val (updatedModules, newAnnotations) = (c.modules.map {
      case m: Module if (m.name == c.main) =>
        val ns = Namespace(m)
        val insts = mutable.Set[WDefInstance]()
        InstanceGraph.collectInstances(insts)(m.body)
        val hubModelInst = insts.find(_.module == hubModel.name).get

        val (promotedTuples, ports, connects) = (for ((name, PortMetadata(rT, dir, tpe)) <- controlAnno.signals) yield {
          val topPort = Port(NoInfo, ns.newName(name), dir, tpe)
          val connect = if (dir == Output) {
            Connect(NoInfo, WRef(topPort), WSubField(WRef(hubModelInst), rT.ref))
          } else {
            Connect(NoInfo, WSubField(WRef(hubModelInst), rT.ref), WRef(topPort))
          }
          val promotedTuple = (name, PortMetadata(rT.copy(module = c.main, ref = topPort.name), dir, tpe))
          (promotedTuple, topPort, connect)
        }).unzip3

        val updatedAnno = SimulationControlAnnotation(promotedTuples.toMap)
        val updatedModule = m.copy(ports = m.ports ++ ports, body = Block(m.body +: connects.toSeq))
        (updatedModule, Some(updatedAnno))
      case om => (om, None)
    }).unzip

    val filteredAnnos = state.annotations.filterNot(_ == controlAnno)
    val newCircuit = c.copy(modules = updatedModules)
    CircuitState(newCircuit, outputForm, filteredAnnos ++ newAnnotations.flatten, None)
  }
}
