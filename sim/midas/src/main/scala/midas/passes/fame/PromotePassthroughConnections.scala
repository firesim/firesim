// See LICENSE for license details.

package midas.passes.fame

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.traversals.Foreachers._
import firrtl.stage.Forms
import firrtl.options.Dependency
import firrtl.transforms.{CheckCombLoops, LogicNode}

import scala.collection.mutable

/** Removes all non-passthrough expressions in a circuit so that we can use
  *  CheckCombLoops to return only passthrough connectivity (this represents
  *  subset of of all combinationally connected paths).
  *
  * @param circuit the circuit to simplify
  */
object RemoveNonWirePrimitives {
  def apply(circuit: Circuit): Circuit = {
    def onExpr(e: Expression): Expression = e.map(onExpr) match {
      // Reject all subfield accesses on a memory
      // case WSubField(WSubField(_,_,_,_),_,_,_) => EmptyExpression
      // This throws a match error in LogicNode creation... Follow up with Albert.

      // All other ones should occur on module instances; leave those
      case sf: WSubField => sf
      case sa: WSubAccess => sa
      case si: WSubIndex => si
      case r: WRef => r
      case o => EmptyExpression
    }
    def onStmt(s: Statement): Statement = s.map(onStmt).map(onExpr) match {
      // Break paths through memories by forcing them to have non-zero latency.
      case mem: DefMemory => mem.copy(readLatency = 1)
      case o => o
    }

    def onModule(m: DefModule): DefModule = m.map(onStmt)
    circuit.map(onModule)
  }
}

/**
  * After [[ExtractModel]] it is common to have passthrough paths (i.e.,
  * identity combinational paths) that snake through the hub and multiple
  * satellites, potentially increasing FMR.
  *
  * This pass pulls these into the FAME wrapper module so that they can be
  * excised, and eventually implemented with a set of channels that fanout from
  * the actual source driver.
  *
  */

object PromotePassthroughConnections extends Transform with DependencyAPIMigration {
  // Note: Currently the explicit transform list in [[midas.passes.MidasTransforms]] prevents any
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
        case o => o.foreach(collectInstances(insts))
      }
    }

    val modelInstances = new mutable.ArrayBuffer[WDefInstance]()
    topModule.foreach(collectInstances(modelInstances))
    val instanceNames = modelInstances.map(_.name).toSet

    val modelNodes = modelInstances.flatMap { case WDefInstance(_, instName, modName, _) =>
      moduleMap(modName).ports
        .filterNot(_.tpe == ClockType)
        .map(p => p.direction -> LogicNode(p.name, inst = Some(instName)))
    }
    val modelSources = modelNodes.collect { case (Output, ln) => ln }
    val modelSinks   = modelNodes.collect { case (Input, ln) => ln }

    val bridgeSources = topModule.ports.collect {
      case Port(_, name, Input, tpe) if tpe != ClockType => LogicNode(name)
    }
    val bridgeSinks   = topModule.ports.collect {
      case Port(_, name, Output, tpe) if tpe != ClockType => LogicNode(name)
    }

    val allSinks = modelSinks ++ bridgeSinks
    val allSources = modelSources ++ bridgeSources

    val simplifiedState = state.copy(circuit = RemoveNonWirePrimitives(state.circuit))
    val connectivity = new CheckCombLoops().analyzeFull(simplifiedState)(state.circuit.main)
    val originalSources = allSources.filter { s => connectivity.reachableFrom(s).isEmpty }.toSet

    val sink2sourceMap = (for (sink <- allSinks) yield {
      val source = connectivity.reachableFrom(sink) & originalSources
      assert(source.size == 1)
      sink -> source.head
    }).toMap


    def onStmt(s: Statement): Statement = s.map(onStmt) match {
      case c@Connect(_, lhs, _) =>
        val sinkNode = lhs match {
          case WSubField(WRef(instName,_,InstanceKind,_), portName, tpe, _) if tpe != ClockType =>
            Some(LogicNode(portName, Some(instName)))
          case WRef(name, tpe, _, _) if tpe != ClockType => Some(LogicNode(name))
          case o => None
        }
        sinkNode match {
          case Some(sinkNode) =>
            val sourceNode = sink2sourceMap(sinkNode)
            val newRHS = sourceNode match {
              case LogicNode(portName, Some(instName), None) => WSubField(WRef(instName), portName)
              case LogicNode(portName, None, None) => WRef(portName)
              case o => throw new Exception(s"memport field of source LogicNode should be unset.") 
            }
            c.copy(expr = newRHS)
          case None => c
        }
      case o => o
    }

    val updatedTopModule = topModule.map(onStmt)
    state.copy(circuit = state.circuit.copy(modules =
      updatedTopModule +: state.circuit.modules.filterNot(_.name == state.circuit.main)))
  }
}
