// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.traversals.Foreachers._
import firrtl.annotations._
import firrtl.annotations.TargetToken.{OfModule, Instance, Field}
import firrtl.stage.Forms
import firrtl.options.Dependency
import firrtl.transforms.{CheckCombLoops, LogicNode}

import scala.collection.mutable

/** A utility for finding the upstream drivers of arbitrary clock signals in a circuit.
  * find and return that input port.
  *
  * @param state the CircuitState to analyze
  */
object RemoveNonWirePrimitives {
  def apply(state: CircuitState): CircuitState = {
    def onExpr(e: Expression): Expression = e.map(onExpr) match {
      case sf: WSubField => sf
      case si: WSubIndex => si
      case sa: WSubAccess => sa
      case r: WRef => r
      case o => EmptyExpression
    }
    def onStmt(s: Statement): Statement = s.map(onStmt).map(onExpr)
    def onModule(m: DefModule): DefModule = m.map(onStmt)
    state.copy(circuit = state.circuit.map(onModule))
  }
}


object PromotePassthroughConnections extends Transform with DependencyAPIMigration {
  // Note: the explicit transform list in MidasTransforms prevents any
  // scheduling of these passes in a different order. Define these for
  // future-proofing & documentation.
	override def prerequisites = Forms.LowForm :+ Dependency[ExtractModel]
  override def optionalPrerequisiteOf = Seq(Dependency[FAMEDefaults])

  def execute(state: CircuitState): CircuitState = {
    val moduleMap = state.circuit.modules.map(m => m.name -> m).toMap
    val topModule = moduleMap(state.circuit.main)

    def collectInstances(insts: mutable.ArrayBuffer[WDefInstance])(s: Statement): Unit = {
      s match {
        case wdef: WDefInstance => insts += wdef
        case o => Nil
      }
      s.foreach(collectInstances(insts))
    }

    val modelInstances = new mutable.ArrayBuffer[WDefInstance]()
    topModule.foreach(collectInstances(modelInstances))
    val instanceNames = modelInstances.map(_.name).toSet

    val modelNodes = modelInstances.flatMap { case WDefInstance(_, instName, modName, _) =>
      println(instName)
      moduleMap(modName).ports.map(p => p.direction -> LogicNode(p.name, inst = Some(instName)))
    }
    val modelSources = modelNodes.collect { case (Output, ln) => ln }
    val modelSinks   = modelNodes.collect { case (Input, ln) => ln }

    val bridgeSources = topModule.ports.collect { case Port(_, name, Input, _) => LogicNode(name) }
    val bridgeSinks   = topModule.ports.collect { case Port(_, name, Output, _) => LogicNode(name) }

    val allSinks = modelSinks ++ bridgeSinks
    val allSources = modelSources ++ bridgeSources

    val connectivity = new CheckCombLoops().analyzeFull(RemoveNonWirePrimitives(state))(state.circuit.main)

    val originalSources = allSources.filter { s => connectivity.reachableFrom(s).isEmpty }.toSet

    val sink2sourceMap = (for (sink <- allSinks) yield {
      val source = connectivity.reachableFrom(sink) & originalSources
      assert(source.size == 1)
      sink -> source.head
    }).toMap


    def onStmt(s: Statement): Statement = s.map(onStmt) match {
      case c@Connect(_, lhs, _) =>
        val sinkNode = lhs match {
          case WSubField(WRef(instName,_,InstanceKind,_), portName, _, _) => LogicNode(portName, Some(instName))
          case WRef(name, _, _, _) => LogicNode(name)
          case o => ???
        }

        val sourceNode = sink2sourceMap(sinkNode)
        val newRHS = sourceNode match {
          case LogicNode(portName, Some(instName), None) => WSubField(WRef(instName), portName)
          case LogicNode(portName, None, None) => WRef(portName)
          case o => throw new Exception(s"memport field of source LogicNode should be unset.") 
        }
        c.copy(expr = newRHS)
      case o => o
    }

    val updatedTopModule = topModule.map(onStmt)
    state.copy(circuit = state.circuit.copy(modules =
      updatedTopModule +: state.circuit.modules.filterNot(_.name == state.circuit.main)))
  }
}
