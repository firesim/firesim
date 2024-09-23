package midas.passes.partition

import java.io.File
import java.io.FileWriter
import scala.collection.mutable
import midas.{FireAxePartitionGlobalInfo, FireAxePartitionIndex, FireAxePreserveTarget}
import midas.targetutils._
import firrtl._
import firrtl.ir._
import firrtl.graph._
import firrtl.transforms.{ExtModulePathAnnotation, LogicNode}
import firrtl.Utils.throwInternalError
import firrtl.annotations._
import firrtl.annotations.TargetToken._
import firrtl.options.Dependency
import firrtl.analyses.InstanceKeyGraph

object LogicGraphTypes {
  type StrConnMap = DiGraph[String] with EdgeData[String, Info]
}

object CheckCombLogger {
  import firrtl.transforms.CheckCombLoops._

  val debugFile   = new File("midas/test-outputs/debug.log")
  val debugWriter = new java.io.FileWriter(debugFile)

  val debugFile2   = new File("midas/test-outputs/debug-2.log")
  val debugWriter2 = new java.io.FileWriter(debugFile2)

  def writeLine(s: String, writer: FileWriter): Unit = {
    writer.write(s)
    writer.write("\n")
  }

  def debug(s: String): Unit = {
// println(s)
    writeLine(s, debugWriter)
    Logger.flush()
  }

  def debug2(s: String): Unit = {
    writeLine(s, debugWriter2)
    Logger.flush()
  }

  def printSimplifiedGraphs(gs: mutable.HashMap[String, AbstractConnMap]): Unit = {
    gs.foreach { case (n, g) =>
      debug2(s"Module ${n}")
      printSimplifiedGraph(g)
    }
  }

  def printSimplifiedGraph(g: AbstractConnMap): Unit = {
    g.getVertices.foreach { v =>
      g.getEdges(v).foreach { u =>
        debug2(s"${v.name} -> ${u.name}")
      }
    }
  }

  def printInternalDeps(gs: mutable.HashMap[String, ConnMap]): Unit = {
    gs.foreach { case (n, g) =>
      debug2(s"InternalDep For Module ${n}")
      printInternalDep(g)
    }
  }

  def printInternalDep(g: ConnMap): Unit = {
    g.getVertices.foreach { v =>
      g.getEdges(v).foreach { u =>
        debug2(s"${v.name} -> ${u.name}")
      }
    }
  }

  def printGraph(g: DiGraph[LogicNode]): Unit = {
    g.getVertices.foreach { v =>
      g.getEdges(v).foreach { u =>
        debug2(s"${v} -> ${u}")
      }
    }
  }

  def flush(): Unit = {
    debugWriter.flush()
    debugWriter2.flush()
  }

  def close(): Unit = {
    flush()
    debugWriter.close()
    debugWriter2.close()
  }
}

class CheckCombLogic extends Transform with DependencyAPIMigration {
  override def prerequisites = firrtl.stage.Forms.MidForm ++
    Seq(Dependency(passes.LowerTypes), Dependency(firrtl.transforms.RemoveReset))

  import firrtl.transforms.CheckCombLoops._
  import PartitionModulesInfo._

  private def getExprDeps(deps: MutableConnMap, v: LogicNode, info: Info)(e: Expression): Unit = e match {
    case r: WRef      => deps.addEdgeIfValid(v, LogicNode(r), info)
    case s: WSubField => deps.addEdgeIfValid(v, LogicNode(s), info)
    case _            => e.foreachExpr(getExprDeps(deps, v, info)(_))
  }

  private def getStmtDeps(
    simplifiedModules: mutable.Map[String, AbstractConnMap],
    deps:              MutableConnMap,
  )(s:                 Statement
  ): Unit = s match {
    case Connect(info, loc, expr)             =>
      val lhs = LogicNode(loc)
      if (deps.contains(lhs)) {
        getExprDeps(deps, lhs, info)(expr)
      }
    case w: DefWire                           =>
      deps.addVertex(LogicNode(w.name))
    case DefNode(info, name, value)           =>
      val lhs = LogicNode(name)
      deps.addVertex(lhs)
      getExprDeps(deps, lhs, info)(value)
    case m: DefMemory if (m.readLatency == 0) =>
      for (rp <- m.readers) {
        val dataNode = deps.addVertex(LogicNode("data", Some(m.name), Some(rp)))
        val addr     = LogicNode("addr", Some(m.name), Some(rp))
        val en       = LogicNode("en", Some(m.name), Some(rp))
        deps.addEdge(dataNode, deps.addVertex(addr), m.info)
        deps.addEdge(dataNode, deps.addVertex(en), m.info)
      }
    case i: WDefInstance                      =>
      val iGraph = simplifiedModules(i.module).transformNodes(n => n.copy(inst = Some(i.name)))
      iGraph.getVertices.foreach(deps.addVertex(_))
      iGraph.getVertices.foreach({ v =>
        iGraph.getEdges(v).foreach { u =>
          CheckCombLogger.debug(s"${i.name} ${v} -> ${u}")
          deps.addEdge(v, u)
        }
      })
    case _                                    =>
      s.foreachStmt(getStmtDeps(simplifiedModules, deps)(_))
  }

  protected def getCombDep(state: CircuitState): (
    mutable.HashMap[String, ConnMap],
    mutable.HashMap[String, AbstractConnMap],
  ) = {
    val c                      = state.circuit
    val extModulePaths         = state.annotations.groupBy {
      case ann: ExtModulePathAnnotation => ModuleTarget(c.main, ann.source.module)
      case _: Annotation                => CircuitTarget(c.main)
    }
    val moduleMap              = c.modules.map({ m => (m.name, m) }).toMap
    val iKeyGraph              = InstanceKeyGraph(c)
    val iGraph                 = iKeyGraph.graph
    val topoSortedModules      = iGraph.transformNodes(_.module).linearize.reverse.map { moduleMap(_) }
    val moduleGraphs           = new mutable.HashMap[String, ConnMap]
    val simplifiedModuleGraphs = new mutable.HashMap[String, AbstractConnMap]
    topoSortedModules.foreach {
      case em: ExtModule =>
        val portSet       = em.ports.map(p => LogicNode(p.name)).toSet
        val extModuleDeps = new MutableDiGraph[LogicNode] with MutableEdgeData[LogicNode, Info]
        portSet.foreach(extModuleDeps.addVertex(_))
        extModulePaths.getOrElse(ModuleTarget(c.main, em.name), Nil).collect { case a: ExtModulePathAnnotation =>
          extModuleDeps.addPairWithEdge(LogicNode(a.sink.ref), LogicNode(a.source.ref))
        }
        moduleGraphs(em.name) = extModuleDeps
        simplifiedModuleGraphs(em.name) = extModuleDeps.simplify(portSet)
      case m: Module     =>
        val portSet      = m.ports.map(p => LogicNode(p.name)).toSet
        val internalDeps = new MutableDiGraph[LogicNode] with MutableEdgeData[LogicNode, Info]
        portSet.foreach(internalDeps.addVertex(_))
        CheckCombLogger.debug(s"CheckCombLogic m.name ${m.name}")
        m.body.foreachStmt(getStmtDeps(simplifiedModuleGraphs, internalDeps)(_))
        moduleGraphs(m.name)           = internalDeps
        simplifiedModuleGraphs(m.name) = moduleGraphs(m.name).simplify(portSet)
      case m             => throwInternalError(s"Module ${m.name} has unrecognized type")
    }
// CheckCombLogger.printSimplifiedGraphs(simplifiedModuleGraphs)
// CheckCombLogger.printInternalDeps(moduleGraphs)
    (moduleGraphs, simplifiedModuleGraphs)
  }

  private def run(state: CircuitState, modNames: Seq[String]): CircuitState = {
    CheckCombLogger.debug(s"Check comb logic for ${modNames}")
    val (moduleGraphs, simplifiedModuleGraphs) = getCombDep(state)
    val c                                      = state.circuit
    val iKeyGraph                              = InstanceKeyGraph(c)
    val combAnnos                              = mutable.ArrayBuffer[Annotation]()
    modNames.foreach({ modName =>
      val instKeyPath                    = iKeyGraph.findInstancesInHierarchy(modName)
      assert(instKeyPath.size == 1, "Should only have a single instance in module hierarchy")
      val rootInstKey                    = instKeyPath.head.dropRight(1).last
      val curInstKey                     = instKeyPath.head.last
      val smg                            = simplifiedModuleGraphs(curInstKey.module)
      val portsWithCombLogicInsideModule = smg.getVertices.flatMap { v =>
        val deps = smg.getEdges(v).toSeq
        if (deps.size > 0) deps :+ v
        else deps
      }
      val combLogicAnnos                 = portsWithCombLogicInsideModule.map { v =>
        FirrtlCombLogicInsideModuleAnno(
          ReferenceTarget(state.circuit.main, rootInstKey.module, Seq(), v.name, Seq(OfModule(curInstKey.module)))
        )
      }
      combAnnos ++= combLogicAnnos
    })
    val newAnnos                               = state.annotations ++ combAnnos.toSeq
    state.copy(annotations = newAnnos)
  }

  def execute(state: CircuitState): CircuitState = {
    CheckCombLogger.debug("Starting CheckCombLogic Pass")
    val (groups, _) = getGroups(state)
    val result      = run(state, groups)
    CheckCombLogger.close()
    result
  }
}

class CheckCombPathLength extends CheckCombLogic {
  import firrtl.transforms.CheckCombLoops._
  import PartitionModulesInfo._

  private def BFS(
    mg:     DiGraph[LogicNode],
    sg:     DiGraph[LogicNode],
    op:     LogicNode,
    iPorts: Set[LogicNode],
    oPorts: Set[LogicNode],
  ): Set[LogicNode] = {
    val reachable = mutable.ArrayBuffer[LogicNode]()
    val vis       = mutable.Set[LogicNode]()
    val q         = mutable.ArrayBuffer[LogicNode]()
    q.append(op)
    while (q.size != 0) {
      val front = q.remove(0)
      vis.add(front)
      val edges = mg.getEdges(front) ++ sg.getEdges(front)
      CheckCombLogger.debug2(s"front ${front} edges ${edges}")
      edges.foreach { adj =>
        CheckCombLogger.debug2(s"front ${front} adj ${adj}")
        if (!vis.contains(adj) && !(oPorts.contains(adj) && iPorts.contains(front))) {
          vis.add(adj)
          q.append(adj)
          reachable.append(adj)
        }
      }
    }
    reachable.foreach { ln => CheckCombLogger.debug2(s"${ln}") }
    reachable.toSet
  }

  private def checkCombPathLen(
    inst: String,
    smg:  AbstractConnMap,
    mg:   ConnMap,
    psmg: AbstractConnMap,
  ): Unit = {
    CheckCombLogger.debug2(s"checkCombPathLen ${inst}")

    val combOPorts = smg.getVertices.flatMap { v =>
      val deps = smg.getEdges(v).toSeq
      if (deps.size > 0) Some(v)
      else None
    }.toSet
    CheckCombLogger.debug2(s"combOPorts ${combOPorts}")

    val combIPorts = smg.getVertices.flatMap { v =>
      smg.getEdges(v).toSeq
    }.toSet
    CheckCombLogger.debug2(s"combIPorts ${combIPorts}")

    val fmg   = mg.reverse
    val fpsmg = psmg.reverse
    CheckCombLogger.debug2("printInternalDep")
    CheckCombLogger.printGraph(fmg)
    CheckCombLogger.debug2("printSimplifiedGraph")
    CheckCombLogger.printGraph(fpsmg)

    val combIPorts2 = combIPorts.map { ip =>
      ip.copy(inst = Some(inst))
    }
    CheckCombLogger.debug2(s"combIPorts2 ${combIPorts2}")

    combOPorts.foreach { op =>
      CheckCombLogger.debug2(s"op ${op}")
      val op2       = op.copy(inst = Some(inst))
      val deps      = BFS(fmg, fpsmg, op2, combIPorts, combOPorts)
      val intersect = deps.intersect(combIPorts2)
      CheckCombLogger.debug2(s"deps ${deps}")
      CheckCombLogger.debug2(s"intersect ${intersect}")
      if (intersect.size >= 1) {
        CheckCombLogger.close()
      }
      assert(
        intersect.size < 1,
        """The length of combinational dependency chain btw ports
        should be less than or equal to 2.
        """,
      )
    }
  }

  private def run(state: CircuitState, modNames: Seq[String]): CircuitState = {
    val (moduleGraphs, simplifiedModuleGraphs) = getCombDep(state)
    val c                                      = state.circuit
    val iKeyGraph                              = InstanceKeyGraph(c)
    modNames.foreach({ modName =>
      val instKeyPath = iKeyGraph.findInstancesInHierarchy(modName)
      assert(instKeyPath.size == 1, "Should only have a single instance in module hierarchy")

      val rootInstKey = instKeyPath.head.dropRight(1).last
      val curInstKey  = instKeyPath.head.last
      CheckCombLogger.debug2(s"rootInstKey ${rootInstKey} curInstKey ${curInstKey}")

      // Check if there are combinational paths between the partitioned modules
      // there the path length is larger than 2.
      val smg  = simplifiedModuleGraphs(curInstKey.module)
      val mg   = moduleGraphs(rootInstKey.module)
      val psmg = simplifiedModuleGraphs(rootInstKey.module)
      val inst = curInstKey.name
      checkCombPathLen(inst, smg, mg, psmg)
    })
    state
  }

  override def execute(state: CircuitState): CircuitState = {
    val p              = getConfigParams(state.annotations)
    val preserveTarget = p(FireAxePreserveTarget)
    if (preserveTarget) {
      val pglob            = p(FireAxePartitionGlobalInfo).get
      val pidx             = p(FireAxePartitionIndex)
      val partitionModules = pidx match {
        case Some(idx) => pglob(idx)
        case None      => pglob.flatten
      }
      CheckCombLogger.debug2(s"modules to check for comb stuff ${partitionModules}")
      run(state, partitionModules)
    } else {
      state
    }
  }
}
