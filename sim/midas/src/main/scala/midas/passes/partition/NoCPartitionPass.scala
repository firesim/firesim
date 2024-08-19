package midas.passes.partition

import scala.Console.println
import scala.collection.mutable
import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.annotations._
import firrtl.annotations.TargetToken._
import firrtl.analyses.{InstanceGraph, InstanceKeyGraph}
import midas._
import midas.stage._
import midas.targetutils._
import midas.passes.EmitFirrtl

// The passes here assumes that we are attaching two CutBoundaryBridges for each partition,
// and the partitioned are connected in a ring/mesh topology

class LowerStatePass extends Transform with DependencyAPIMigration with StateToLowFIRRTLPass {
  def execute(state: CircuitState): CircuitState = {
    toLowFirrtl(removePartial(state))
  }
}

object PartitionNoCRouters {
  private val name  = "PartWrappers"
  private var index = -1

  def NoCModule: String               = "NoC"
  def tileHartIdNexusNodeInst: String = "tileHartIdNexusNode"
  def DigitalTop: String              = "DigitalTop"
  def CoherenceManagerWrapper: String = "CoherenceManagerWrapper"

  def setupNextPartitionWrapper: Unit = (index += 1)
  def partWrapperInst: String         = s"${name}_${index}"
  def partWrapperModule: String       = s"${name}_${index}"
  def nextPartWrapperInst: String     = s"${name}_${index + 1}"
  def nextPartWrapperModule: String   = s"${name}_${index + 1}"
  def prevPartWrapperInst: String     = s"${name}_${index - 1}"
  def prevPartWrapperModule: String   = s"${name}_${index - 1}"

  def getClockResetSfx(name: String): String = {
    val len = name.length
    if (len < 5) name
    else name.substring(len - 5, len)
  }

  def getNoCRouterIndices(groups: Seq[Seq[String]]): Seq[Seq[Int]] = {
    groups.map(g => g.map(_.toInt))
  }
}

trait NoCGroupModulesToPartitionPass {
  import PartitionNoCRouters._

  /*
   * |-----------------------|
   * |                       |
   * |  |-----|    |-----|   |
   * |  |     | <- |     |   |
   * |  |     | -> |     |   |
   * |  |-----|    |-----|   |
   * |     ^          |      |
   * |     |          V      |
   * |  |-----|    |-----|   |
   * |  |     |    |     |   |
   * |  |     |    |     |   |
   * |  |-----|    |-----|   |
   * |                       |
   * |-----------------------|
   *
   * |-----------------------|
   * | |------------------|  |
   * | ||-----|    |-----||  |
   * | ||     | <- |     ||  |
   * | ||     | -> |     ||  |
   * | ||-----|    |-----||  |
   * | |---^----------|---|  |
   * |     |          V      |
   * |  |-----|    |-----|   |
   * |  |     |    |     |   |
   * |  |     |    |     |   |
   * |  |-----|    |-----|   |
   * |                       |
   * |-----------------------|
   */

  def groupModulesToPartition(
    instKeys: mutable.LinkedHashMap[Instance, OfModule],
    module:   Module,
  ): (
    Seq[Statement],
    Module,
    Map[String, String],
    Map[String, String],
  ) = {
    PartitionNoCRouters.setupNextPartitionWrapper

    val instanceNames                      = instKeys.map { _._1.value }.toSet
    val intraPartConns                     = mutable.ArrayBuffer[Statement]()
    val partWrapperPorts                   = mutable.ArrayBuffer[Port]()
    val partWrapperPortsToInstConns        = mutable.ArrayBuffer[Statement]()
    val partWrapperInstDef                 = DefInstance(NoInfo, partWrapperInst, partWrapperModule)
    val refToPartWrapperPortMap            = mutable.Map[String, String]()
    val partWrapperPortToOriginalRouterMap = mutable.Map[String, String]()

    val replaceInstanceWithWrapper = module.body.map((stmt: Statement) =>
      stmt match {
        case s: DefInstance if (instanceNames.contains(s.name))                                   =>
          EmptyStmt
        case s @ Connect(_, WSubField(WRef(li), lref, lt, lf), WSubField(WRef(ri), rref, rt, rf)) =>
          if (instanceNames.contains(li._1) && instanceNames.contains(ri._1)) { // connections btw modules to wrap
            intraPartConns.append(Connect(NoInfo, WSubField(WRef(li._1), lref), WSubField(WRef(ri._1), rref)))
            EmptyStmt
          } else if (instanceNames.contains(li._1)) { // module to wrap & other module
            val portName = li._1 + "_" + lref
            partWrapperPorts.append(Port(NoInfo, portName, firrtl.ir.Input, lt))
            partWrapperPortsToInstConns.append(Connect(NoInfo, WSubField(WRef(li._1), lref), WRef(portName)))
            refToPartWrapperPortMap(lref)                = portName
            partWrapperPortToOriginalRouterMap(portName) = li._1
            Connect(NoInfo, WSubField(WRef(partWrapperInst), portName), WSubField(WRef(ri._1), rref, rt, rf))
          } else if (instanceNames.contains(ri._1)) { // module to wrap & other module
            val portName = ri._1 + "_" + rref
            partWrapperPorts.append(Port(NoInfo, portName, firrtl.ir.Output, rt))
            partWrapperPortsToInstConns.append(Connect(NoInfo, WRef(portName), WSubField(WRef(ri._1), rref)))
            refToPartWrapperPortMap(rref)                = portName
            partWrapperPortToOriginalRouterMap(portName) = ri._1
            Connect(NoInfo, WSubField(WRef(li._1), lref, lt, lf), WSubField(WRef(partWrapperModule), portName))
          } else {
            s
          }
        case s @ Connect(
              _,
              WSubField(WRef(li), lref, _, _),
              DoPrim(op, Seq(WSubField(WRef(ri), rref, _, _)), consts, tpe),
            ) =>
          if (instanceNames.contains(li._1) && instanceNames.contains(ri._1)) { // connections btw modules to wrapp that has some primops in btw
            intraPartConns.append(
              Connect(NoInfo, WSubField(WRef(li._1), lref), DoPrim(op, Seq(WSubField(WRef(ri._1), rref)), consts, tpe))
            )
            EmptyStmt
          } else {
            s
          }
        case s @ Connect(_, WSubField(WRef(li), lref, lt, _), rexp)                               =>
          if (instanceNames.contains(li._1)) { // module to wrap & other module
            val portName = li._1 + "_" + lref
            partWrapperPorts.append(Port(NoInfo, portName, firrtl.ir.Input, lt))
            partWrapperPortsToInstConns.append(Connect(NoInfo, WSubField(WRef(li._1), lref), WRef(portName)))
            refToPartWrapperPortMap(lref) = portName
            Connect(NoInfo, WSubField(WRef(partWrapperInst), portName), rexp)
          } else {
            s
          }
        case s @ Connect(_, lexp, WSubField(WRef(ri), rref, rt, _))                               =>
          if (instanceNames.contains(ri._1)) { // module to wrap & other module
            val portName = ri._1 + "_" + rref
            partWrapperPorts.append(Port(NoInfo, portName, firrtl.ir.Output, rt))
            partWrapperPortsToInstConns.append(Connect(NoInfo, WRef(portName), WSubField(WRef(ri._1), rref)))
            refToPartWrapperPortMap(rref) = portName
            Connect(NoInfo, lexp, WSubField(WRef(partWrapperInst), portName))
          } else {
            s
          }
        case s @ Connect(_, lexp, rexp @ DoPrim(_, Seq(WSubField(WRef(ri), rref, rt, _)), _, _))  =>
          if (instanceNames.contains(ri._1)) { // module to wrap & other module with primops in btw
            val portName = ri._1 + "_" + rref
            partWrapperPorts.append(Port(NoInfo, portName, firrtl.ir.Output, rt))
            partWrapperPortsToInstConns.append(Connect(NoInfo, WRef(portName), rexp))
            Connect(NoInfo, lexp, WSubField(WRef(partWrapperInst), portName))
          } else {
            s
          }
        case s @ DefNode(_, name, WSubField(WRef(ri), rref, rt, _))                               =>
          if (instanceNames.contains(ri._1)) { // module to wrap & other module with defnode in btw
            val portName = ri._1 + "_" + rref
            partWrapperPorts.append(Port(NoInfo, portName, firrtl.ir.Output, rt))
            partWrapperPortsToInstConns.append(Connect(NoInfo, WRef(portName), WSubField(WRef(ri._1), rref)))
            DefNode(NoInfo, name, WSubField(WRef(partWrapperInst), portName))
          } else {
            s
          }
        case s                                                                                    =>
          s
      }
    )

    val newModuleBody = Seq(partWrapperInstDef, replaceInstanceWithWrapper)
    val instDefs      = instKeys.map { ik =>
      DefInstance(NoInfo, ik._1.value, ik._2.value)
    }
    val instConns     = (partWrapperPortsToInstConns ++ intraPartConns).toSeq
    val wrapperBody   = Block((instDefs ++ instConns).toSeq)
    val wrapperModule = Module(NoInfo, partWrapperModule, partWrapperPorts.toSeq, wrapperBody)

    (newModuleBody, wrapperModule, refToPartWrapperPortMap.toMap, partWrapperPortToOriginalRouterMap.toMap)
  }
}

class NoCPartitionRoutersPass extends Transform with DependencyAPIMigration with NoCGroupModulesToPartitionPass {
  import PartitionNoCRouters._

  private def routerInstNameToIdx(name: String): Int = {
    if (name == "router_sink_domain") 0
    else name.substring(19, name.length).toInt
  }

  private def checkInterRouterConns(
    annos:               mutable.ArrayBuffer[Annotation],
    routerToGroupIdx:    Map[Int, Int],
    wrapperPortToRouter: Map[String, String],
    circuitMain:         String,
  )(stmt:                Statement
  ): Unit = {
    stmt match {
      case Connect(_, WSubField(WRef(li), lref, _, _), WSubField(WRef(ri), rref, _, _)) =>
        if (ri._1 == partWrapperInst) {
          val neighborIdx      = routerInstNameToIdx(li._1)
          val neighborGroupIdx = routerToGroupIdx(neighborIdx)
          val curGroupIdx      = routerToGroupIdx(routerInstNameToIdx(wrapperPortToRouter(rref)))
          val rt               = ReferenceTarget(circuitMain, NoCModule, Seq(), rref, Seq(OfModule(partWrapperModule)))
          annos.append(FirrtlPortToNeighborRouterIdxAnno(rt, neighborGroupIdx, curGroupIdx))
        } else if (li._1 == partWrapperInst) {
          val neighborIdx      = routerInstNameToIdx(ri._1)
          println(s"neighborIdx ${neighborIdx}")
          println(s"routerToGroupIdx ${routerToGroupIdx}")
          val neighborGroupIdx = routerToGroupIdx(neighborIdx)
          val curGroupIdx      = routerToGroupIdx(routerInstNameToIdx(wrapperPortToRouter(lref)))
          val rt               = ReferenceTarget(circuitMain, NoCModule, Seq(), lref, Seq(OfModule(partWrapperModule)))
          annos.append(FirrtlPortToNeighborRouterIdxAnno(rt, neighborGroupIdx, curGroupIdx))
        } else {
          ()
        }
      case s: Block                                                                     =>
        s.foreachStmt(ss => checkInterRouterConns(annos, routerToGroupIdx, wrapperPortToRouter, circuitMain)(ss))
      case _                                                                            => ()
    }
  }

  // NOTE: This function assumes how constellation generates a NoC (NoC.scala)
  // It connects router_sink_domains directly, and defines wires that connects the
  // router_sink_domains and the NoC ports. This pass removes these wires and
  // replaces them with direct connections between the router_sink_domain &
  // NoC ports
  def execute(state: CircuitState): CircuitState = {
    val igraph     = InstanceKeyGraph(state.circuit)
    val moduleDefs = state.circuit.modules.collect({ case m: Module => OfModule(m.name) -> m }).toMap

    val allNoCInstKeyPaths = igraph.findInstancesInHierarchy(NoCModule)
    assert(allNoCInstKeyPaths.size == 1, "Currently only supports a single NoC configuration")
    val nocInstKeyPath     = allNoCInstKeyPaths.head
    val nocInstKey         = nocInstKeyPath.last

    // Child instances of the NoC should be router clock sink domains
    val routerSinkDomainInsts = igraph.getChildInstanceMap(nocInstKey.OfModule)

    val p                    = state.annotations
      .collectFirst({ case midas.stage.phases.ConfigParametersAnnotation(p) =>
        p
      })
      .get
    val routerIndicesByGroup = getNoCRouterIndices(p(FireAxePartitionGlobalInfo).get)
    val groupCount           = routerIndicesByGroup.size

    // when performing the remove pass, extract all the router nodes that is not in the current group
    val routerIndicesPartition = p(FireAxePartitionIndex) match {
      case Some(idx) => routerIndicesByGroup(idx)
      case None      =>
        val ridx = groupCount - 1
        (0 until groupCount).filter(_ != ridx).flatMap { idx =>
          routerIndicesByGroup(idx)
        }
    }

    // FIXME : This is brittle
    val routerGroupInsts = routerIndicesPartition.map { idx =>
      if (idx == 0) "router_sink_domain"
      else s"router_sink_domain_${idx}"
    }.toSet

    val routerToGroupIdx = routerIndicesByGroup.zipWithIndex.flatMap { case (indices, gidx) =>
      indices.map { idx => idx -> gidx }
    }.toMap

    println(s"routerToGroupIdx ${routerToGroupIdx}")

    val partitionRoutersInstKeys = routerSinkDomainInsts.filter { inst =>
      routerGroupInsts.contains(inst._1.value)
    }

    val (newNoCBody, wrapperModule, _, wrapperPortToRouter) =
      groupModulesToPartition(partitionRoutersInstKeys, moduleDefs(nocInstKey.OfModule))
    val transformedModules                                  = state.circuit.modules.flatMap {
      case m: Module if (m.name == NoCModule) =>
        Seq(m.copy(body = Block(newNoCBody)), wrapperModule)
      case m                                  => Seq(m)
    }

    val wrapperParentAnno = FirrtlPartWrapperParentAnnotation(
      InstanceTarget(
        state.circuit.main,
        nocInstKeyPath.dropRight(1).last.OfModule.value,
        Seq(),
        nocInstKey.Instance.value,
        nocInstKey.OfModule.value,
      )
    )

    val portToNeighborAnnos = mutable.ArrayBuffer[Annotation]()
    newNoCBody.foreach { stmt =>
      checkInterRouterConns(portToNeighborAnnos, routerToGroupIdx, wrapperPortToRouter, state.circuit.main)(stmt)
    }

    val transformedAnnos   = state.annotations ++ portToNeighborAnnos.toSeq :+ wrapperParentAnno
    val transformedCircuit = state.circuit.copy(modules = transformedModules)
    state.copy(circuit = transformedCircuit, annotations = transformedAnnos)
  }
}

class NoCReparentRouterGroupPass extends Transform with DependencyAPIMigration {
  import PartitionNoCRouters._

  def execute(state: CircuitState): CircuitState = {
    reparentPartWrapper(state)
  }

  // Assuming that there is no intermediate wires between the module to reparent and curModule's IO ports,
  // it pulls out the wrapper module up into the parModule.
  // |----------|    |------
  // |    |     |    |     |         |----|
  // |    |-----| => |     |----|  + |    |
  // |          |    |          |    |----|
  // |----------|    |----------|
  protected def reparentPartWrapper(state: CircuitState): CircuitState = {
    val igraph             = InstanceKeyGraph(state.circuit)
    val wrapperInstKeyPath = igraph.findInstancesInHierarchy(partWrapperInst)
    val curModule          = wrapperInstKeyPath.head.dropRight(1).last.module
    val parModule          = wrapperInstKeyPath.head.dropRight(2).last.module

    // TODO : rename stuff here
    val allNoCInstKeyPaths = igraph.findInstancesInHierarchy(curModule)
    assert(allNoCInstKeyPaths.size == 1, "Currently only supports a single NoC configuration")
    val nocInstKeyPath     = allNoCInstKeyPaths.head
    val nocInstKey         = nocInstKeyPath.last
    val moduleDefs         = state.circuit.modules.collect({ case m: Module => OfModule(m.name) -> m }).toMap
    val nocModuleDef       = moduleDefs(nocInstKey.OfModule)

    val nocModulePorts        = nocModuleDef.ports
    val nocModulePortNames    = nocModulePorts.map(_.name).toSet
    val portsToAdd            = mutable.ArrayBuffer[Port]()
    val portsToRemove         = mutable.Set[String]()
    val parentConns           = mutable.ArrayBuffer[Connect]()
    val nocToWrapperPorts     = mutable.Map[String, mutable.ArrayBuffer[String]]()
    val nocToWrapperPrimOps   = mutable.Map[String, (PrimOp, Seq[BigInt], Type)]()
    val wrapperRemovedNoCBody = nocModuleDef.body.map((stmt: Statement) =>
      stmt match {
        case DefInstance(_, _, mname, _) if (mname == partWrapperModule)                               =>
          EmptyStmt
        case Connect(_, WRef(lref), WSubField(WRef(ri), rref, _, _))
            if (
              nocModulePortNames.contains(lref._1) &&
                ri._1 == partWrapperInst
            ) =>
          // move the port to the wrapper side
          if (!nocToWrapperPorts.contains(lref._1))
            nocToWrapperPorts(lref._1) = mutable.ArrayBuffer[String]()
          nocToWrapperPorts(lref._1).append(rref)
          portsToRemove.add(lref._1)
          EmptyStmt
        case Connect(_, WSubField(WRef(li), lref, _, _), WRef(rref))
            if (
              nocModulePortNames.contains(rref._1) &&
                li._1 == partWrapperInst
            ) =>
          // move the port to the wrapper side
          if (!nocToWrapperPorts.contains(rref._1))
            nocToWrapperPorts(rref._1) = mutable.ArrayBuffer[String]()
          nocToWrapperPorts(rref._1).append(lref)
          portsToRemove.add(rref._1)
          EmptyStmt
        case s @ Connect(_, WSubField(WRef(li), lref, lt, _), WSubField(WRef(ri), rref, rt, _))        =>
          // punch ports in the original module, and connect the original module with the wrapper in the parent module
          if (li._1 == partWrapperInst) {
            val portName = ri._1 + "_" + rref
            portsToAdd.append(Port(NoInfo, portName, firrtl.ir.Output, rt))
            parentConns.append(
              Connect(NoInfo, WSubField(WRef(partWrapperInst), lref), WSubField(WRef(nocInstKey.name), portName))
            )
            Connect(NoInfo, WRef(portName), WSubField(WRef(ri._1), rref))
          } else if (ri._1 == partWrapperInst) {
            val portName = li._1 + "_" + lref
            portsToAdd.append(Port(NoInfo, portName, firrtl.ir.Input, lt))
            parentConns.append(
              Connect(NoInfo, WSubField(WRef(nocInstKey.name), portName), WSubField(WRef(partWrapperInst), rref))
            )
            Connect(NoInfo, WSubField(WRef(li._1), lref), WRef(portName))
          } else {
            s
          }
        case s @ Connect(_, lexp, WSubField(WRef(ri), rref, rt, _))                                    =>
          // punch ports in the original module, and connect the original module with the wrapper in the parent module
          if (ri._1 == partWrapperInst) {
            val portName = ri._1 + "_" + rref
            portsToAdd.append(Port(NoInfo, portName, firrtl.ir.Input, rt))
            parentConns.append(
              Connect(NoInfo, WSubField(WRef(nocInstKey.name), portName), WSubField(WRef(partWrapperInst), rref))
            )
            Connect(NoInfo, lexp, WRef(portName))
          } else {
            s
          }
        case s @ Connect(_, WSubField(WRef(li), lref, _, _), DoPrim(op, Seq(WRef(rref)), consts, tpe)) =>
          // move the port to the wrapper side, perform the current primop in the parent module
          if (li._1 == partWrapperInst && nocModulePortNames.contains(rref._1)) {
            if (!nocToWrapperPorts.contains(rref._1))
              nocToWrapperPorts(rref._1) = mutable.ArrayBuffer[String]()
            nocToWrapperPorts(rref._1).append(lref)
            portsToRemove.add(rref._1)
            nocToWrapperPrimOps(rref._1) = (op, consts, tpe)
            EmptyStmt
          } else {
            s
          }
        case s @ DefNode(_, name, WSubField(WRef(ri), rref, rt, _))                                    =>
          if (ri._1 == partWrapperInst) {
            val portName = ri._1 + "_" + rref
            portsToAdd.append(Port(NoInfo, portName, firrtl.ir.Input, rt))
            parentConns.append(
              Connect(NoInfo, WSubField(WRef(nocInstKey.name), portName), WSubField(WRef(partWrapperInst), rref))
            )
            DefNode(NoInfo, name, WRef(portName))
          } else {
            s
          }
        case s                                                                                         => s
      }
    )

    val newNoCPorts     = nocModulePorts.filter { p =>
      val clockOrReset = getClockResetSfx(p.name)
      !portsToRemove.contains(p.name) || (clockOrReset == "clock") || (clockOrReset == "reset")
    } ++ portsToAdd.toSet.toSeq
    val newNocBody      = Block(wrapperRemovedNoCBody)
    val newNoCModuleDef = nocModuleDef.copy(body = newNocBody, ports = newNoCPorts)

    val nocClockSources          = mutable.Map[String, firrtl.ir.Expression]()
    val nocResetSources          = mutable.Map[String, firrtl.ir.Expression]()
    val protocolNocModuleDef     = moduleDefs(OfModule(parModule))
    val wrapperPulledProtocolNoC = protocolNocModuleDef.body.map((stmt: Statement) =>
      stmt match {
        case Connect(_, WSubField(WRef(li), lref, _, _), rexp)
            if (
              li._1 == nocInstKey.name &&
                portsToRemove.contains(lref)
            ) =>
          val clockOrReset = getClockResetSfx(lref)
          if (clockOrReset == "clock") {
            nocClockSources(lref) = rexp
          } else if (clockOrReset == "reset") {
            nocResetSources(lref) = rexp
          }
          val wrapperPorts = nocToWrapperPorts(lref)
          val conns        = wrapperPorts.map { wrapperPort =>
            val rhs = if (nocToWrapperPrimOps.contains(lref)) {
              val (op, consts, tpe) = nocToWrapperPrimOps(lref)
              DoPrim(op, Seq(rexp), consts, tpe)
            } else {
              rexp
            }
            Connect(NoInfo, WSubField(WRef(partWrapperInst), wrapperPort), rhs)
          }
          Block(conns.toSeq)
        case Connect(_, lexp, WSubField(WRef(ri), rref, _, _))
            if (
              ri._1 == nocInstKey.name &&
                portsToRemove.contains(rref)
            ) =>
          val wrapperPorts = nocToWrapperPorts(rref)
          val conns        = wrapperPorts.map { wrapperPort =>
            Connect(NoInfo, lexp, WSubField(WRef(partWrapperInst), wrapperPort))
          }
          Block(conns.toSeq)
        case s => s
      }
    )

    val clockConns = nocClockSources.map { case (portName, src) =>
      Connect(NoInfo, WSubField(WRef(nocInstKey.Instance.value), portName), src)
    }
    val resetConns = nocResetSources.map { case (portName, src) =>
      Connect(NoInfo, WSubField(WRef(nocInstKey.Instance.value), portName), src)
    }

    val newProtocolNoCBody      = Block(
      Seq(DefInstance(NoInfo, partWrapperInst, partWrapperModule), wrapperPulledProtocolNoC) ++
        clockConns.toSeq ++
        resetConns.toSeq ++
        parentConns.toSeq
    )
    val newProtocolNoCModuleDef = protocolNocModuleDef.copy(body = newProtocolNoCBody)
    val transformedModules      = state.circuit.modules.map {
      case m: Module if (m.name == curModule) =>
        newNoCModuleDef
      case m: Module if (m.name == parModule) =>
        newProtocolNoCModuleDef
      case m                                  => m
    }

    val transformedCircuit = state.circuit.copy(modules = transformedModules)
    state.copy(circuit = transformedCircuit)
  }
}

class RemoveDirectWireConnectionPass extends Transform with DependencyAPIMigration {
  import PartitionNoCRouters._

  protected def execute(state: CircuitState): CircuitState = {
    val igraph             = InstanceKeyGraph(state.circuit)
    val allNoCInstKeyPaths = igraph.findInstancesInHierarchy(NoCModule)
    assert(allNoCInstKeyPaths.size == 1, "Currently only supports a single NoC configuration")

    val nocInstKeyPath = allNoCInstKeyPaths.head.takeRight(4)
    val nocMods        = nocInstKeyPath.map(_.module).toSet
    val newCircuit     = state.circuit.map((x: DefModule) =>
      x match {
        case m: Module if (nocMods.contains(m.name)) => onModule(m)
        case m                                       => m
      }
    )
    state.copy(circuit = newCircuit)
  }

  private def onModule(m: Module): Module = {
    val wires = mutable.ArrayBuffer[String]()
    m.body.foreachStmt(stmt =>
      stmt match {
        case w: DefWire => wires.append(w.name)
        case _          => ()
      }
    )

    val wireLHS     = mutable.Map[String, firrtl.ir.Expression]()
    val wireRHS     = mutable.Map[String, firrtl.ir.Expression]()
    val removeWires = m.body.map((stmt: Statement) =>
      stmt match {
        case _: DefWire                                            => EmptyStmt
        case Connect(_, WRef(lref), rexp) if (lref._3 == WireKind) =>
          wireRHS(lref._1) = rexp
          EmptyStmt
        case Connect(_, lexp, WRef(rref)) if (rref._3 == WireKind) =>
          wireLHS(rref._1) = lexp
          EmptyStmt
        case s                                                     => s
      }
    )

    val directConns = wires.map { wname =>
      val lhs = wireLHS(wname)
      val rhs = wireRHS(wname)
      Connect(NoInfo, lhs, rhs)
    }

    val newBody = Block(Seq(removeWires) ++ directConns.toSeq)
    m.copy(body = newBody)
  }
}

class NoCCollectModulesInPathAndRegroupPass extends NoCReparentRouterGroupPass with NoCGroupModulesToPartitionPass {
  import PartitionNoCRouters._

  private var cnt = 0

  private def BFS(
    root:          String,
    avoidInst:     Set[String],
    instConnGraph: mutable.Map[String, mutable.Set[String]],
  ): mutable.Set[String] = {
    val queue   = mutable.ArrayBuffer[String]()
    val visited = mutable.Set[String]()
    queue.append(root)

    while (queue.size != 0) {
      val front = queue.remove(0)
      visited.add(front)
      val edges = instConnGraph(front)

      edges.foreach { adj =>
        println(s"igraph ${front} -> ${adj}")
        if (!visited.contains(adj) && !avoidInst.contains(adj)) {
          queue.append(adj)
        }
      }
    }
    visited
  }

  // NOTE : This pass assumes that the modules that will be grouped together with the partition wrapper is
  // located on the edges of the module.
  override def execute(state: CircuitState): CircuitState = {
    groupAndReparent(state)
  }

  def groupAndReparent(state: CircuitState): CircuitState = {
    val igraph             = InstanceKeyGraph(state.circuit)
    val wrapperInstKeyPath = igraph.findInstancesInHierarchy(partWrapperInst)
    val curModuleInstKey   = wrapperInstKeyPath.head.dropRight(1).last

    val resolver          = new ResolveAndCheck
    val splitFixerEmitter = new EmitFirrtl(s"post-split-fixer-${cnt}.fir")
    val findGroupEmitter  = new EmitFirrtl(s"post-findgroup-and-wrap-${cnt}.fir")
    val reparentEmitter   = new EmitFirrtl(s"post-reparent-partwrapper-${cnt}.fir")
    println(s"cnt ${cnt} curModule ${curModuleInstKey.module}")
    cnt += 1

    if (curModuleInstKey.module == DigitalTop) {
      val fixerSplitState = splitFifoFixer(state)
      splitFixerEmitter.runTransform(fixerSplitState)

      val wrappedState = findGroupAndWrap(resolver.runTransform(fixerSplitState))
      findGroupEmitter.runTransform(wrappedState)
    } else {
      val fixerSplitState = splitFifoFixer(state)
      splitFixerEmitter.runTransform(fixerSplitState)

      val wrappedState = findGroupAndWrap(resolver.runTransform(fixerSplitState))
      findGroupEmitter.runTransform(wrappedState)

      val reparentedState = reparentPartWrapper(resolver.runTransform(wrappedState))
      reparentEmitter.runTransform(reparentedState)

      groupAndReparent(resolver.runTransform(reparentedState))
    }
  }

  private def getFixerModuleName(name: String, idx: Int): String = {
    name + s"Split_${idx}"
  }

  private def splitFifoFixer(state: CircuitState): CircuitState = {
    val igraph             = InstanceKeyGraph(state.circuit)
    val wrapperInstKeyPath = igraph.findInstancesInHierarchy(partWrapperInst)
    val curModuleInstKey   = wrapperInstKeyPath.head.dropRight(1).last
    val childInstances     = igraph.getChildInstanceMap(curModuleInstKey.OfModule)

    // FIXME : Better way to identify TLFIFOFixer?
    val fixers = childInstances.filter { inst =>
      (inst._1.value == "fixer") ||
      (inst._2.value.contains("TLFIFOFixer"))
    }
    assert(fixers.size < 2, "Can only have a single fixer per module at the moment")

    if (fixers.size > 0) {
      val moduleDefs                  = state.circuit.modules.collect({ case m: Module => OfModule(m.name) -> m }).toMap
      val fixer                       = moduleDefs(fixers.head._2)
      val fixerInst                   = fixers.head._1.value
      val (splitFixers, portToIdxMap) = splitFifoFixerModuleDef(fixer)
      val curModule                   = moduleDefs(curModuleInstKey.OfModule)
      val numFixers                   = splitFixers.size

      val splitFixerDefs     = (0 until numFixers).map { idx =>
        val fixerModuleName = getFixerModuleName(fixer.name, idx)
        DefInstance(NoInfo, fixerModuleName, fixerModuleName)
      }
      val replaceFixer       = curModule.body.mapStmt(stmt =>
        stmt match {
          case DefInstance(_, _, mname, _) if (mname == fixer.name)                       =>
            EmptyStmt
          case Connect(_, WSubField(WRef(li), lref, _, _), rexpr) if (li._1 == fixerInst) =>
            if (lref == "clock" || lref == "reset") {
              val conns = (0 until numFixers).map { idx =>
                val fixerName = getFixerModuleName(fixer.name, idx)
                Connect(NoInfo, WSubField(WRef(fixerName), lref), rexpr)
              }
              Block(conns)
            } else {
              val fixerIdx  = portToIdxMap(lref)
              val fixerName = getFixerModuleName(fixer.name, fixerIdx)
              Connect(NoInfo, WSubField(WRef(fixerName), lref), rexpr)
            }
          case Connect(_, lexpr, WSubField(WRef(ri), rref, _, _)) if (ri._1 == fixerInst) =>
            val fixerIdx  = portToIdxMap(rref)
            val fixerName = getFixerModuleName(fixer.name, fixerIdx)
            Connect(NoInfo, lexpr, WSubField(WRef(fixerName), rref))
          case Connect(_, lexpr, DoPrim(op, Seq(WSubField(WRef(ri), rref, _, _)), consts, tpe))
              if (ri._1 == fixerInst) =>
            val fixerIdx  = portToIdxMap(rref)
            val fixerName = getFixerModuleName(fixer.name, fixerIdx)
            Connect(NoInfo, lexpr, DoPrim(op, Seq(WSubField(WRef(fixerName), rref)), consts, tpe))
          case s                                                                          => s
        }
      )
      val newBody            = Block(splitFixerDefs.toSeq ++ Seq(replaceFixer))
      val transformedModules = state.circuit.modules.flatMap {
        case m: Module if (m.name == curModuleInstKey.module) =>
          Seq(m.copy(body = newBody)) ++ splitFixers
        case m                                                => Seq(m)
      }

      val transformedCircuit = state.circuit.copy(modules = transformedModules)
      state.copy(circuit = transformedCircuit)
    } else {
      state
    }
  }

  // Assume that all the stuff are decoupled for now
  private def splitFifoFixerModuleDef(fixer: Module): (Seq[Module], Map[String, Int]) = {
    val ports              = fixer.ports
    val nonClockResetPorts = ports.filter(p => p.name != "clock" && p.name != "reset")

    // HACK : This is very hacky, basically it looks at the port name,
    // searchs for a number, and uses the first number to group ports
    val portToIndexMap = nonClockResetPorts.map { p =>
      val idx = ("""\d+""".r.findAllIn(p.name)).toList.head.toInt
      p -> idx
    }.toMap

    val exprMap        = mutable.Map[String, Int]()
    portToIndexMap.foreach { pidx =>
      exprMap(pidx._1.name) = pidx._2
    }
    val registerDefs   = mutable.Set[DefRegister]()
    val regNameToIndex = mutable.Map[String, Int]()
    val stmtMap        = mutable.Map[Statement, (Int, Int)]()
    val stmtLine       = mutable.Map[Statement, Int]()
    var line           = 0
    fixer.body.foreachStmt { s =>
      stmtLine(s) = line
      line += 1
    }

    // search for dependent statements
    def onStmt(stmt: Statement): Unit = {
      stmt match {
        case s @ DefRegister(_, _, _, _, _, _)      =>
          registerDefs.add(s)
        case s @ DefNode(_, name, expr)             =>
          expr.foreachExpr(childexpr =>
            childexpr match {
              case WRef(ref) if (exprMap.contains(ref._1)) =>
                val idx = exprMap(ref._1)
                exprMap(name) = idx
                stmtMap(s)    = (idx, stmtLine(s))
              case _                                       => ()
            }
          )
        case s @ Connect(_, WRef(lref), WRef(rref)) =>
          val idx = exprMap(rref._1)
          exprMap(lref._1) = idx
          stmtMap(s)       = (idx, stmtLine(s))

          if (registerDefs.map(_.name).contains(lref._1)) {
            val idx = exprMap(rref._1)
            regNameToIndex(lref._1) = idx
          }
        case s @ Connect(_, WRef(lref), expr)       =>
          expr.foreachExpr(childexpr =>
            childexpr match {
              case WRef(ref) if (exprMap.contains(ref._1)) =>
                val idx = exprMap(ref._1)
                exprMap(lref._1) = idx
                stmtMap(s)       = (idx, stmtLine(s))

                if (registerDefs.map(_.name).contains(lref._1)) {
                  regNameToIndex(lref._1) = idx
                }
              case _                                       => ()
            }
          )
        case _                                      => ()
      }
    }

    // have to execute this twice because dependencies may be hidden
    // because of registers (initially, we don't know what group each
    // register belongs to
    fixer.body.foreachStmt(onStmt(_))
    fixer.body.foreachStmt(onStmt(_))

    // search for register definitions
    registerDefs.foreach { defreg =>
      val idx = regNameToIndex(defreg.name)
      stmtMap(defreg) = (idx, stmtLine(defreg))
    }

    val splitFixers = stmtMap.groupBy(_._2._1).map { hm =>
      val idx        = hm._1
      val stmts      = hm._2.toSeq.sortBy(_._2._2).map(_._1)
      val fixerPorts = nonClockResetPorts.filter { port =>
        val pidx = portToIndexMap(port)
        (pidx == idx)
      } ++ Seq(
        Port(NoInfo, "clock", firrtl.ir.Input, ClockType),
        Port(NoInfo, "reset", firrtl.ir.Input, UIntType(IntWidth(1))),
      )
      val fixerBody  = Block(stmts)
      Module(NoInfo, getFixerModuleName(fixer.name, idx), fixerPorts, fixerBody)
    }

    val portNameToIndexMap = portToIndexMap.map { p2idx =>
      p2idx._1.name -> p2idx._2
    }.toMap
    (splitFixers.toSeq, portNameToIndexMap)
  }

  private def findGroupAndWrap(state: CircuitState): CircuitState = {
    val igraph             = InstanceKeyGraph(state.circuit)
    val wrapperInstKeyPath = igraph.findInstancesInHierarchy(partWrapperInst)
    val curModuleInstKey   = wrapperInstKeyPath.head.dropRight(1).last

    val moduleDefs     = state.circuit.modules.collect({ case m: Module => OfModule(m.name) -> m }).toMap
    val curModuleDef   = moduleDefs(curModuleInstKey.OfModule)
    val instConnGraph  = mutable.Map[String, mutable.Set[String]]()
    val childInstances = igraph.getChildInstanceMap(curModuleInstKey.OfModule)

    childInstances.foreach { inst =>
      instConnGraph(inst._1.value) = mutable.Set[String]()
    }
    curModuleDef.body.foreachStmt(getInstConnGraph(instConnGraph)(_))

    // FIXME : don't really like the .get here. But I guess it is okay since
    // we perform the NoCPartitionRoutersPass first.
    // HACK : just ignore certain modules(extraAvoidInstances) since we kind of know the SoC structure anyways
    val wrapperParentAnno   = state.annotations
      .collectFirst({ case FirrtlPartWrapperParentAnnotation(it) =>
        it
      })
      .get
    val avoidInstance       = wrapperParentAnno.instance
    val intSourceNames      = childInstances
      .filter(x =>
        x._1.value.contains("plic_domain") ||
          x._1.value.contains("clint_domain")
      )
      .map(_._1.value)
    val extraAvoidInstances = intSourceNames.toSeq :+ "fixedClockNode"
    val allAvoidInstances   = (Seq(avoidInstance) ++ extraAvoidInstances).toSet

    val reachableInsts    = BFS(partWrapperInst, allAvoidInstances, instConnGraph)
    reachableInsts.foreach { inst => println(s"reachable ${inst}") }
    val partGroupInstKeys = childInstances.filter { inst =>
      reachableInsts.contains(inst._1.value)
    }

    val (newModuleBody, wrapperModule, portMap, _) = groupModulesToPartition(partGroupInstKeys, curModuleDef)
    val transformedModules                         = state.circuit.modules.flatMap {
      case m: Module if (m.name == curModuleInstKey.module) =>
        Seq(m.copy(body = Block(newModuleBody)), wrapperModule)
      case m                                                => Seq(m)
    }
    val transformedAnnos                           = state.annotations.map {
      case FirrtlPartWrapperParentAnnotation(_)            =>
        val target = InstanceTarget(
          state.circuit.main,
          wrapperInstKeyPath.head.dropRight(2).last.module,
          Seq(),
          curModuleInstKey.Instance.value,
          curModuleInstKey.OfModule.value,
        )
        FirrtlPartWrapperParentAnnotation(target)
      case a @ FirrtlPortToNeighborRouterIdxAnno(rt, _, _) =>
        val newPortName = portMap(rt.ref)
        val newRt       = rt.copy(
          module    = curModuleInstKey.OfModule.value,
          ref       = newPortName,
          component = Seq(OfModule(partWrapperModule)),
        )
        a.copy(target = newRt)
      case anno                                            => anno
    }
    val transformedCircuit                         = state.circuit.copy(modules = transformedModules)
    state.copy(circuit = transformedCircuit, annotations = transformedAnnos)
  }

  private def getInstConnGraph(
    instConnGraph: mutable.Map[String, mutable.Set[String]]
  )(stmt:          Statement
  ): Unit = {
    stmt match {
      case Connect(_, WSubField(WRef(li), _, _, _), WSubField(WRef(ri), _, _, _)) =>
        instConnGraph(ri._1).add(li._1)
        instConnGraph(li._1).add(ri._1)
      case b: Block                                                               => b.foreachStmt(getInstConnGraph(instConnGraph)(_))
      case _                                                                      => ()
    }
  }
}

class DedupClockAndResetPass extends Transform with DependencyAPIMigration with NoCGroupModulesToPartitionPass {
  import PartitionNoCRouters._

  def execute(state: CircuitState): CircuitState = {
    val igraph               = InstanceKeyGraph(state.circuit)
    val wrapperInstKeyPath   = igraph.findInstancesInHierarchy(partWrapperInst)
    val curModuleInstKey     = wrapperInstKeyPath.head.dropRight(1).last
    val wrapperModuleInstKey = wrapperInstKeyPath.head.last

    val moduleDefs    = state.circuit.modules.collect({ case m: Module => OfModule(m.name) -> m }).toMap
    val curModuleDef  = moduleDefs(curModuleInstKey.OfModule)
    val newModuleBody = curModuleDef.body.map((stmt: Statement) =>
      stmt match {
        case Connect(_, WSubField(WRef(li), _, _, _), WRef(rref))
            if (
              li._1 == partWrapperInst &&
                getClockResetSfx(rref._1) == "clock"
            ) =>
          Connect(NoInfo, WSubField(WRef(nextPartWrapperInst), "clock"), WRef(rref._1))
        case Connect(_, WSubField(WRef(li), _, _, _), WRef(rref))
            if (
              li._1 == partWrapperInst &&
                getClockResetSfx(rref._1) == "reset"
            ) =>
          Connect(NoInfo, WSubField(WRef(nextPartWrapperInst), "reset"), WRef(rref._1))
        case Connect(_, WSubField(WRef(li), lref, _, _), WSubField(WRef(ri), rref, _, _))
            if (
              li._1 == partWrapperInst
            ) =>
          if (getClockResetSfx(rref) == "clock") {
            Connect(NoInfo, WSubField(WRef(nextPartWrapperInst), "clock"), WSubField(WRef(ri._1), rref))
          } else if (getClockResetSfx(rref) == "reset") {
            Connect(NoInfo, WSubField(WRef(nextPartWrapperInst), "reset"), WSubField(WRef(ri._1), rref))
          } else {
            Connect(NoInfo, WSubField(WRef(nextPartWrapperInst), lref), WSubField(WRef(ri._1), rref))
          }
        case Connect(_, WSubField(WRef(li), lref, _, _), WSubField(WRef(ri), rref, _, _))
            if (
              ri._1 == partWrapperInst
            ) =>
          Connect(NoInfo, WSubField(WRef(li._1), lref), WSubField(WRef(nextPartWrapperInst), rref))
        case DefInstance(_, iname, _, _) if (iname == partWrapperInst) =>
          DefInstance(NoInfo, nextPartWrapperInst, nextPartWrapperModule)
        case s                                                         => s
      }
    )

    setupNextPartitionWrapper
    val curWrapperModule         = moduleDefs(wrapperModuleInstKey.OfModule)
    val noClockResetWrapperPorts = curWrapperModule.ports.filter { port =>
      val sfx = getClockResetSfx(port.name)
      sfx != "clock" && sfx != "reset"
    }

    val dedupedWrapperPorts =
      noClockResetWrapperPorts ++
        Seq(Port(NoInfo, "clock", firrtl.ir.Input, ClockType), Port(NoInfo, "reset", firrtl.ir.Input, ResetType))

    val defInst            = DefInstance(NoInfo, prevPartWrapperInst, prevPartWrapperModule)
    val conns              = curWrapperModule.ports.map { port =>
      if (getClockResetSfx(port.name) == "clock")
        Connect(NoInfo, WSubField(WRef(prevPartWrapperInst), port.name), WRef("clock"))
      else if (getClockResetSfx(port.name) == "reset")
        Connect(NoInfo, WSubField(WRef(prevPartWrapperInst), port.name), WRef("reset"))
      else if (port.direction == firrtl.ir.Input)
        Connect(NoInfo, WSubField(WRef(prevPartWrapperInst), port.name), WRef(port.name))
      else
        Connect(NoInfo, WRef(port.name), WSubField(WRef(prevPartWrapperInst), port.name))
    }
    val wrapperBody        = Block(Seq(defInst) ++ conns)
    val wrapperModule      = Module(NoInfo, partWrapperModule, dedupedWrapperPorts, wrapperBody)
    val transformedModules = state.circuit.modules.flatMap {
      case m: Module if (m.name == curModuleInstKey.module) =>
        Seq(m.copy(body = Block(newModuleBody)), wrapperModule)
      case m                                                => Seq(m)
    }
    val transformedCircuit = state.circuit.copy(modules = transformedModules)

    // for future ExtractModulePass or RemoveModulePass
    val extractModuleAnnotation = ExtractModuleNameAnnotation(partWrapperModule)
    val removeModuleAnnotation  = RemoveModuleNameAnnotation(partWrapperModule)

    state.annotations.map(anno =>
      anno match {
        case a @ FirrtlPortToNeighborRouterIdxAnno(rt, _, _) =>
          val newRt = rt.copy(component = Seq(OfModule(partWrapperModule)))
          a.copy(target = newRt)
        case a                                               => a
      }
    )
    val annos = state.annotations :+ extractModuleAnnotation :+ removeModuleAnnotation
    state.copy(circuit = transformedCircuit, annotations = annos)
  }
}

class NoCConnectInterruptsPass extends Transform with DependencyAPIMigration {
  import PartitionNoCRouters._

  protected def execute(state: CircuitState): CircuitState = {
    val igraph             = InstanceKeyGraph(state.circuit)
    val wrapperInstKeyPath = igraph.findInstancesInHierarchy(partWrapperInst)
    val curModuleInstKey   = wrapperInstKeyPath.head.dropRight(1).last
    val childInstances     = igraph.getChildInstanceMap(curModuleInstKey.OfModule)

    // FIXME : Better way to identify IntSyncCrossingSource?
    val intsources = childInstances.filter { inst =>
      inst._1.value.contains("plic_domain") ||
      inst._1.value.contains("clint_domain")
    }
    val tiles      = childInstances.filter { inst =>
      (inst._2.value.contains("TilePRCIDomain"))
    }

    println(s"NoCConnectInterruptsPass tiles: ${tiles}")
    println(s"NoCConnectInterruptsPass intsources: ${intsources}")

    val moduleDefs   = state.circuit.modules.collect({ case m: Module => OfModule(m.name) -> m }).toMap
    val curModuleDef = moduleDefs(curModuleInstKey.OfModule)

    val tileSet                   = tiles.map { tile => tile._1.value }.toSet
    val intSourceSet              = intsources.map { int => int._1.value }.toSet
    val tileToIntSourceMap        = mutable.Map[String, mutable.Set[String]]()
    val wrapperConsumedIntSources = mutable.Set[String]()
    curModuleDef.body.map((stmt: Statement) =>
      stmt match {
        case s @ Connect(_, WSubField(WRef(li), lref, _, _), WSubField(WRef(ri), _, _, _)) =>
          if (tileSet.contains(li._1) && intSourceSet.contains(ri._1)) {
            if (!tileToIntSourceMap.contains(li._1)) tileToIntSourceMap(li._1) = mutable.Set[String]()
            tileToIntSourceMap(li._1).add(ri._1)
          } else if (li._1 == partWrapperInst && intSourceSet.contains(ri._1)) {
            wrapperConsumedIntSources.add(lref)
          }
          s
        case s                                                                             => s
      }
    )

    tileToIntSourceMap.foreach { entr =>
      println(s"tileToInterruptSources ${entr}")
    }
    wrapperConsumedIntSources.foreach { s =>
      println(s"wrapperConsumedIntSources ${s}")
    }

    // HACK : This assumes that the router node indices matches the core indices.
    // E.g., core 0 is connected to router 0, core 1 is connected to router 1...
    // NOTE : Can we fix this using the output files from Constellation?
    // Also, assumes that the base SoC is mapped to the last group (groupCnt - 1).
    /*
     * 0            : 0 ~ (groupCnt - 2)
     * 1            : 1 ~ (groupCnt - 2)
     * groupCnt - 2 : (groupCnt - 2) ~ (groupCnt - 2)
     * groupCnt - 1 : Base Partition
     */
    val p              = state.annotations
      .collectFirst({ case midas.stage.phases.ConfigParametersAnnotation(p) =>
        p
      })
      .get
    val indicesByGroup = getNoCRouterIndices(p(FireAxePartitionGlobalInfo).get)
    val groupCnt       = indicesByGroup.size
    val curGroupIdx    = p(FireAxePartitionIndex) match {
      case Some(idx) => idx
      case None      => groupCnt - 1
    }
    val indexStart     = curGroupIdx + 1
    val indexEnd       = groupCnt - 1
    val indicesToSend  = (indexStart until indexEnd).map(indicesByGroup(_)).flatten
    println(s"indicesToSend ${indicesToSend}")
    assert(
      groupCnt > 2,
      "There is no reason why we want to perform a ring topology partition " +
        "when there are only two FPGAs. Consider using the basic partitioning scheme",
    )

    val passThroughIntPortsToAdd = mutable.ArrayBuffer[(Port, Port)]()
    val newModuleBody            = curModuleDef.body.map((stmt: Statement) =>
      stmt match {
        case s @ Connect(_, WSubField(WRef(li), lref, _, _), WSubField(WRef(ri), rref, rt, _))
            if (
              li._1.contains("tile_prci_domain") &&
                intSourceSet.contains(ri._1)
            ) =>
          val tile_idx = getTilePRCIIndex(li._1)
          println(s"extracted tile_idx ${tile_idx}")

          if (indicesToSend.contains(tile_idx)) {
            val inPortName  = ri._1 + "_in_" + rref
            val outPortName = ri._1 + "_out_" + rref
            val inPort      = Port(NoInfo, inPortName, firrtl.ir.Input, rt)
            val outPort     = Port(NoInfo, outPortName, firrtl.ir.Output, rt)
            passThroughIntPortsToAdd.append((inPort, outPort))

            val intSrcToWrapperInConn =
              Connect(NoInfo, WSubField(WRef(partWrapperInst), inPortName), WSubField(WRef(ri._1), rref))
            val wrapperToTileConn     =
              Connect(NoInfo, WSubField(WRef(li._1), lref), WSubField(WRef(partWrapperInst), outPortName))
            Block(Seq(intSrcToWrapperInConn, wrapperToTileConn))
          } else {
            s
          }
        case s => s
      }
    )

    println(s"passThroughIntPortsToAdd ${passThroughIntPortsToAdd}")

    val partWrapper               = moduleDefs(OfModule(partWrapperModule))
    val newWrapperPorts           = partWrapper.ports.filter { p => wrapperConsumedIntSources.contains(p.name) } ++
      passThroughIntPortsToAdd.flatMap { x => Seq(x._1, x._2) }.toSeq ++
      partWrapper.ports.filter { p => !wrapperConsumedIntSources.contains(p.name) }
    val intPassThroughConnections = passThroughIntPortsToAdd.zipWithIndex
      .map { case (ports, idx) =>
        val inport      = ports._1
        val outport     = ports._2
        val wireName    = s"int_pass_through_${idx}"
        val defWire     = DefWire(NoInfo, wireName, inport.tpe)
        val inPortConn  = Connect(NoInfo, WRef(wireName), WRef(inport.name))
        val outPortConn = Connect(NoInfo, WRef(outport.name), WRef(wireName))
        Seq(defWire, inPortConn, outPortConn)
      }
      .flatten
      .toSeq
    val newWrapperBody            = Block(
      Seq(partWrapper.body) ++ intPassThroughConnections
    )
    val newWrapper                = partWrapper.copy(ports = newWrapperPorts, body = newWrapperBody)

    val transformedModules = state.circuit.modules.map {
      case m: Module if (m.name == curModuleInstKey.module) =>
        curModuleDef.copy(body = newModuleBody)
      case m: Module if (m.name == partWrapperModule)       =>
        newWrapper
      case m                                                => m
    }

    val prevGroupIdx = (curGroupIdx + groupCnt - 1) % groupCnt
    val nextGroupIdx = (curGroupIdx + 1)            % groupCnt

    val intPortNeighborGroupAnnos = mutable.ArrayBuffer[Annotation]()
    newWrapperPorts.filter(port => wrapperConsumedIntSources.contains(port.name)).foreach { port =>
      val rt =
        ReferenceTarget(state.circuit.main, curModuleInstKey.module, Seq(), port.name, Seq(OfModule(partWrapperModule)))
      intPortNeighborGroupAnnos.append(FirrtlPortToNeighborRouterIdxAnno(rt, prevGroupIdx, nextGroupIdx))
    }
    println(s"intPortNeighborGroupAnnos ${intPortNeighborGroupAnnos}")
    passThroughIntPortsToAdd.foreach { ports =>
      val inport  = ports._1
      val outport = ports._2
      val inrt    = ReferenceTarget(
        state.circuit.main,
        curModuleInstKey.module,
        Seq(),
        inport.name,
        Seq(OfModule(partWrapperModule)),
      )
      val outrt   = ReferenceTarget(
        state.circuit.main,
        curModuleInstKey.module,
        Seq(),
        outport.name,
        Seq(OfModule(partWrapperModule)),
      )
      intPortNeighborGroupAnnos.append(FirrtlPortToNeighborRouterIdxAnno(inrt, prevGroupIdx, nextGroupIdx))
      intPortNeighborGroupAnnos.append(FirrtlPortToNeighborRouterIdxAnno(outrt, nextGroupIdx, prevGroupIdx))
    }

    val transformedAnnos   = state.annotations ++ intPortNeighborGroupAnnos.toSeq
    val transformedCircuit = state.circuit.copy(modules = transformedModules)
    state.copy(circuit = transformedCircuit, annotations = transformedAnnos)
  }

  private def getTilePRCIIndex(name: String): Int = {
    println(s"getTilePRCIIndex ${name}")
    val sname = name.split("_")
    if (sname.size < 4) 0 // "tile_prci_domain"
    else sname(3).toInt   // "tile_prci_domain_#
  }
}

// 1. collect hartid value for each tile prci domain
// 2. for each tile prci domain remove the tile hartid port,
//    replace it with a hardcoded wire value,
//    change the connection to the tile reset domain with that value
//
//   output auto_out_3 : UInt<2>
//   node outputs_0 = UInt<2>("h0") @[HasTiles.scala 159:32]
//   auto_out_3 <= outputs_3 @[Nodes.scala 1212:84 BundleBridge.scala 152:67]
class NoCConnectHartIdPass extends Transform with DependencyAPIMigration {
  import PartitionNoCRouters._

  def execute(state: CircuitState): CircuitState = {
    val igraph     = InstanceKeyGraph(state.circuit)
    val moduleDefs = state.circuit.modules.collect({ case m: Module => OfModule(m.name) -> m }).toMap

    // HACK : is there a more general way to find the tileHartIdNexusNode?
    val digiTopPath       = igraph.findInstancesInHierarchy(DigitalTop)
    val digiTopInstKey    = digiTopPath.head.last
    val digiTopChildInsts = igraph.getChildInstanceMap(digiTopInstKey.OfModule)

    val hartIdInstKey       = digiTopChildInsts.filter { inst =>
      inst._1.value == tileHartIdNexusNodeInst
    }.head
    val hartIdPortToDefNode = mutable.Map[String, String]()
    val hartIdDefNodeToIdx  = mutable.Map[String, firrtl.ir.Expression]()
    val hartIdModule        = moduleDefs(hartIdInstKey._2)
    hartIdModule.body.foreachStmt(stmt =>
      stmt match {
        case Connect(_, WRef(lref), WRef(rref)) =>
          hartIdPortToDefNode(lref._1) = rref._1
        case DefNode(_, name, expr)             =>
          hartIdDefNodeToIdx(name) = expr
        case _                                  => ()
      }
    )

    val tilePortToHartIdPortMap = mutable.Map[(String, String), String]()
    val tileToRemovePortMap     = mutable.Map[String, String]()
    val digitTopModule          = moduleDefs(digiTopInstKey.OfModule)
    val removeHartIdDigiTopBody = digitTopModule.body.map((stmt: Statement) =>
      stmt match {
        case Connect(_, WSubField(WRef(li), lref, _, _), WSubField(WRef(ri), rref, _, _))
            if (
              ri._1 == tileHartIdNexusNodeInst
            ) =>
          tilePortToHartIdPortMap((li._1, lref)) = rref
          tileToRemovePortMap(li._1)             = lref
          EmptyStmt
        case DefInstance(_, iname, _, _) if (iname == tileHartIdNexusNodeInst) =>
          EmptyStmt
        case s                                                                 => s
      }
    )

    val tilePRCIs = digiTopChildInsts.filter { inst =>
      inst._1.value.contains("tile_prci_domain")
    }

    val hartIdIncludedTilePRCIModules = tilePRCIs
      .map { ik => (ik._1, moduleDefs(ik._2)) }
      .map { case (iname, tileprci) =>
        val removePortName = tileToRemovePortMap(iname.value)
        val newBody        = tileprci.body.map((stmt: Statement) =>
          stmt match {
            case Connect(_, WSubField(WRef(li), lref, _, _), WRef(rref)) if (rref._1 == removePortName) =>
              val hartIdExpr = hartIdDefNodeToIdx(hartIdPortToDefNode(tilePortToHartIdPortMap((iname.value, rref._1))))
              Connect(NoInfo, WSubField(WRef(li._1), lref), hartIdExpr)
            case s                                                                                      => s
          }
        )
        val newPorts       = tileprci.ports.filter { _.name != removePortName }
        tileprci.name -> tileprci.copy(body = newBody, ports = newPorts)
      }
      .toMap

    val transformedModules = state.circuit.modules.map {
      case m: Module if (m.name == digiTopInstKey.OfModule.value)        =>
        m.copy(body = removeHartIdDigiTopBody)
      case m: Module if (hartIdIncludedTilePRCIModules.contains(m.name)) =>
        hartIdIncludedTilePRCIModules(m.name)
      case m                                                             => m
    }

    val transformedCircuit = state.circuit.copy(modules = transformedModules)
    state.copy(circuit = transformedCircuit)
  }
}
