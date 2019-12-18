// See LICENSE for license details.

package midas.passes

import firrtl._
import firrtl.ir._
import firrtl.annotations._
import firrtl.annotations.TargetToken.{OfModule, Instance}
import firrtl.graph.{DiGraph}
import firrtl.analyses.InstanceGraph

import midas.passes.fame.RTRenamer

case class FindClockSourceAnnotation(
    target: ReferenceTarget,
    originalTarget: Option[ReferenceTarget] = None) extends Annotation {
  require(target.module == target.circuit, s"Queried leaf clock ${target} must provide an absolute instance path")
  def update(renames: RenameMap): Seq[FindClockSourceAnnotation] =
    Seq(this.copy(RTRenamer.exact(renames)(target), originalTarget.orElse(Some(target))))
}

case class ClockSourceAnnotation(queryTarget: ReferenceTarget, source: ReferenceTarget) extends Annotation {
  def update(renames: RenameMap): Seq[ClockSourceAnnotation] = Seq(this.copy(queryTarget, RTRenamer.exact(renames)(source)))
}

object FindClockSources extends firrtl.Transform {
  def inputForm = LowForm
  def outputForm = LowForm
  private def getSourceClock(moduleGraphs: Map[String,DiGraph[LogicNode]],
                             moduleDeps: Map[String, Map[String,String]])
                            (rT: ReferenceTarget): ReferenceTarget = {
    val modulePath   = (OfModule(rT.module) +: rT.path.map(_._2)).reverse
    val instancePath = (None +: rT.path.map(tuple => Some(tuple._1))).reverse

    def walkModule(currentNode: LogicNode, path: Seq[(Option[Instance], OfModule)]): LogicNode = {
      require(path.nonEmpty)
      val (instOpt, module) :: restOfPath = path
      val mGraph = moduleGraphs(module.value)
      val source = if (mGraph.findSinks(currentNode)) currentNode else mGraph.reachableFrom(currentNode).head
      (restOfPath, source) match {
        // Source is a port on the top level module -> we're done
        case (Nil, LogicNode(_, None, _)) => source
        // Source is a port on an instance in our current module; prepend it to the path and recurse
        case (_, LogicNode(port, Some(instName),_)) =>
          val childModule = moduleDeps(module.value)(instName)
          walkModule(LogicNode(port), (Some(Instance(instName)), OfModule(childModule)) +: path)
        // Source is a port but we are not yet at the top; recurse into parent module
        case (nonEmptyPath, _) => walkModule(LogicNode(source.name, instOpt.map(_.value)), nonEmptyPath)
      }
    }
    val sourceClock = walkModule(LogicNode(rT.ref), instancePath.zip(modulePath))
    rT.moduleTarget.ref(sourceClock.name)
  }

  def analyze(state: CircuitState, queryTargets: Seq[ReferenceTarget]): Map[ReferenceTarget, ReferenceTarget] = {
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
    (queryTargets.map(qT => qT -> getSourceClock(clockConnectivity.toMap, moduleDeps)(qT))).toMap
  }

  def execute(state: CircuitState): CircuitState = {
    val queryAnnotations = state.annotations.collect({ case anno: FindClockSourceAnnotation => anno })
    val sourceMappings = analyze(state, queryAnnotations.map(_.target))
    val clockSourceAnnotations = queryAnnotations.map(qAnno =>
      ClockSourceAnnotation(qAnno.originalTarget.getOrElse(qAnno.target), sourceMappings(qAnno.target)))
    val prunedAnnos = state.annotations.flatMap({
      case _: FindClockSourceAnnotation => None
      case o => Some(o)
    })
    state.copy(annotations = clockSourceAnnotations ++ prunedAnnos)
  }
}
