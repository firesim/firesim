// See LICENSE for license details.

package midas.passes

import firrtl._
import firrtl.ir._
import firrtl.annotations._
import firrtl.annotations.TargetToken.{OfModule, Instance}
import firrtl.graph.{DiGraph}
import firrtl.analyses.InstanceGraph

object FindClockSources  {
  private def getSourceClock(moduleGraphs: Map[String,DiGraph[LogicNode]])
                            (rT: ReferenceTarget): ReferenceTarget = {
    val modulePath   = (OfModule(rT.module) +: rT.path.map(_._2)).reverse
    val instancePath = (None +: rT.path.map(tuple => Some(tuple._1))).reverse

    def walkModule(currentNode: LogicNode, path: Seq[(Option[Instance], OfModule)]): LogicNode = {
      require(path.nonEmpty)
      val (instOpt, module) :: restOfPath = path
      val mGraph = moduleGraphs(module.value)
      val source = if (mGraph.findSinks(currentNode)) currentNode else mGraph.reachableFrom(currentNode).head
      require(source.inst == None, "TODO: Handle clocks that may traverse a submodule hierarchy")
      restOfPath match {
        case Nil => source
        case _ => walkModule(LogicNode(source.name, instOpt.map(_.value)), restOfPath)
      }
    }
    val sourceClock = walkModule(LogicNode(rT.ref), instancePath.zip(modulePath))
    rT.moduleTarget.ref(sourceClock.name)
  }

  def apply(state: CircuitState, queryTargets: Seq[ReferenceTarget]): Map[ReferenceTarget, ReferenceTarget] = {
    queryTargets.foreach(t => {
      require(t.component == Nil)
      require(t.module == t.circuit, s"Queried leaf clock ${t} must provide an absolute instance path")
    })
    val c = state.circuit
    val moduleMap = c.modules.map({m => (m.name,m) }).toMap
    val iGraph = new InstanceGraph(c).graph
    val moduleDeps = iGraph.getEdgeMap.map({ case (k,v) => (k.module, (v map { i => (i.name, i.module) }).toMap) }).toMap
    val connectivity = new CheckCombLoops().analyzeFull(state)
    val qTsByModule = queryTargets.groupBy(_.encapsulatingModule)

    val clockPortsByModule = c.modules.map(m => m.name -> m.ports.collect({ case p@Port(_, _, _, ClockType) => LogicNode(p.name) }) ).toMap
    val clockConnectivity = connectivity map { case (module, subgraph) =>
        val clockPorts = clockPortsByModule(module)
        val instClockNodes = moduleDeps(module) flatMap {
          case (inst, module) => clockPortsByModule(module).map(_.copy(inst = Some(inst)))
        }
        val queriedNodes = qTsByModule.getOrElse(module, Seq()).map(rT => LogicNode(rT.ref))
        val simplifiedSubgraph = subgraph.simplify((clockPorts ++ instClockNodes ++ queriedNodes).toSet)
        module -> simplifiedSubgraph
    }
    (queryTargets.map(qT => qT -> getSourceClock(clockConnectivity.toMap)(qT))).toMap
  }
}
