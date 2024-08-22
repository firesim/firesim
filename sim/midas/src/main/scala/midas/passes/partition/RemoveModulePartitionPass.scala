package midas.passes.partition

import scala.Console.println
import scala.collection.mutable
import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.annotations._
import firrtl.analyses.{InstanceGraph, InstanceKeyGraph}
import midas._
import midas.targetutils._

// Remove a module from the hierarchy by replacing the module contents with a cut bridge.
// The "ExtractBridges" pass will pull the CutBridge to the top level of the hierarchy.
// |----------------|     |----------------|
// | |------------| |     | |------------| |
// | | ModuleStmt | |  => | | CutBridge  | |
// | |------------| |     | |------------| |
// |----------------|     |----------------|
//
// - Current behavior / API
// - User provides a list of module names e.g, RocketTile-RocketTile_1-RocketTile_2
// - All instances of "RocketTile", "RocketTile_1", "RocketTile_2" are pulled out

class GenerateCutBridgeInGroupedWrapper extends Transform with DependencyAPIMigration with GenCutBridgePass {
  import PartitionModulesInfo._

  private def replaceRingNoCModuleBodyWithCutBridges(
    cutBridgeAnnos:   mutable.ArrayBuffer[Annotation],
    cutBridgeModules: mutable.ArrayBuffer[ExtModule],
    circuitMain:      String,
    cutBridgeType:    String,
    annos:            AnnotationSeq,
  )(m:                Module
  ): Module = {
    val p           = getConfigParams(annos)
    val nGroups     = p(FireAxePartitionGlobalInfo).get.size
    val curGroupIdx = p(FireAxePartitionIndex) match {
      case Some(idx) => idx
      case None      => nGroups - 1
    }

    val portNameToGroupIdxMap = annos
      .flatMap(anno =>
        anno match {
          case FirrtlPortToNeighborRouterIdxAnno(rt, extractIdx, removeIdx) =>
            println(s"portmap ${rt.ref} ${extractIdx} ${removeIdx}")
            Some(rt.ref -> removeIdx)
          case _                                                            => None
        }
      )
      .toMap

    val ports = m.ports
    ports.foreach(port => println(s"removeModule port ${port.name}"))

    val groupIdxToPorts = mutable.Map[Int, mutable.Set[String]]()
    portNameToGroupIdxMap.foreach { case (pn, gidx) =>
      if (!groupIdxToPorts.contains(gidx)) groupIdxToPorts(gidx) = mutable.Set[String]()
      groupIdxToPorts(gidx).add(pn)
    }

    // Flip lhsGroupIdx rhsGroupIdx for the base partition
    val rhsGroupIdx = (curGroupIdx + 1)           % nGroups
    val lhsGroupIdx = (curGroupIdx + nGroups - 1) % nGroups

    println(s"groupIdxToPorts ${groupIdxToPorts}")
    println(s"lhsGroupIdx ${lhsGroupIdx} curGroupIdx ${curGroupIdx} rhsGroupIdx ${rhsGroupIdx}")

    // FIXME : how should be properly propagate the reset signal ??????
    val lhsPortNames     = groupIdxToPorts(lhsGroupIdx)
    val lhsPorts         = ports.filter { port => lhsPortNames.contains(port.name) }
    val lhsCutBridgeInfo = generateCutBridge(circuitMain, "lhsCutBridge", lhsPorts, inModule = true, cutBridgeType)
    cutBridgeModules.append(lhsCutBridgeInfo.mod)
    cutBridgeAnnos.append(lhsCutBridgeInfo.anno)

    val lhsInPorts      = lhsCutBridgeInfo.inPorts
    val lhsBridgeInst   = lhsCutBridgeInfo.inst
    val lhsInPortsCat   =
      DefNode(info = NoInfo, name = "lhsCutInPortsCat", midas.passes.Utils.orderedCat(lhsInPorts.toSeq))
    val lhsInBridgeConn = Connect(NoInfo, WSubField(WRef(lhsBridgeInst), "io_out"), WRef(lhsInPortsCat.name))

    val lhsOutPorts                 = lhsCutBridgeInfo.outPorts
    val lhsOutPortsBits             = lhsCutBridgeInfo.outPortsBits
    val lhsSliceStartingBits        = lhsOutPortsBits.scanLeft(0)(_ + _)
    val lhsBridgeOutPortsSliced     = lhsSliceStartingBits.zip(lhsOutPortsBits).map { case (startBit, numBits) =>
      DoPrim(
        PrimOps.Bits,
        Seq(WSubField(WRef(lhsBridgeInst), "io_in")),
        Seq(startBit + numBits - 1, startBit),
        firrtl.ir.UIntType(IntWidth(numBits)),
      )
    }
    val lhsOutBridgeConns           = lhsBridgeOutPortsSliced.zip(lhsOutPorts).map { case (bo, op) =>
      Connect(NoInfo, op, bo)
    }
    val lhsOutBridgeClockResetConns = m.ports.map { p =>
      val connection = p.name match {
        case "clock" => Some(Connect(NoInfo, WSubField(WRef(lhsBridgeInst), "clock"), WRef(p.name)))
        case "reset" => Some(Connect(NoInfo, WSubField(WRef(lhsBridgeInst), "reset"), WRef(p.name)))
        case _       => None
      }
      connection
    }.flatten

    // TODO : DEDUP this with above
    val rhsPortNames     = groupIdxToPorts(rhsGroupIdx)
    val rhsPorts         = ports.filter { port => rhsPortNames.contains(port.name) }
    val rhsCutBridgeInfo = generateCutBridge(circuitMain, "rhsCutBridge", rhsPorts, inModule = true, cutBridgeType)
    cutBridgeModules.append(rhsCutBridgeInfo.mod)
    cutBridgeAnnos.append(rhsCutBridgeInfo.anno)

    val rhsInPorts      = rhsCutBridgeInfo.inPorts
    val rhsBridgeInst   = rhsCutBridgeInfo.inst
    val rhsInPortsCat   =
      DefNode(info = NoInfo, name = "rhsCutInPortsCat", midas.passes.Utils.orderedCat(rhsInPorts.toSeq))
    val rhsInBridgeConn = Connect(NoInfo, WSubField(WRef(rhsBridgeInst), "io_out"), WRef(rhsInPortsCat.name))

    val rhsOutPorts                 = rhsCutBridgeInfo.outPorts
    val rhsOutPortsBits             = rhsCutBridgeInfo.outPortsBits
    val rhsSliceStartingBits        = rhsOutPortsBits.scanLeft(0)(_ + _)
    val rhsBridgeOutPortsSliced     = rhsSliceStartingBits.zip(rhsOutPortsBits).map { case (startBit, numBits) =>
      DoPrim(
        PrimOps.Bits,
        Seq(WSubField(WRef(rhsBridgeInst), "io_in")),
        Seq(startBit + numBits - 1, startBit),
        firrtl.ir.UIntType(IntWidth(numBits)),
      )
    }
    val rhsOutBridgeConns           = rhsBridgeOutPortsSliced.zip(rhsOutPorts).map { case (bo, op) =>
      Connect(NoInfo, op, bo)
    }
    val rhsOutBridgeClockResetConns = m.ports.map { p =>
      val connection = p.name match {
        case "clock" => Some(Connect(NoInfo, WSubField(WRef(rhsBridgeInst), "clock"), WRef(p.name)))
        case "reset" => Some(Connect(NoInfo, WSubField(WRef(rhsBridgeInst), "reset"), WRef(p.name)))
        case _       => None
      }
      connection
    }.flatten

    // This is Constellation specific. Constellation has debug registers in each of its routers, and these
    // ports eventually connect to those registers. These registers are irrelavent with performance so we
    // just tie the ports connecting to them.
    val debugPorts = ports.filter { port =>
      !lhsPortNames.contains(port.name) &&
      !rhsPortNames.contains(port.name) &&
      (port.name != "clock") &&
      (port.name != "reset")
    }

    val debugPortsTieDownConns = debugPorts.map { dp =>
      val width = dp.tpe match {
        case UIntType(w) => w
        case _           => IntWidth(BigInt(64))
      }
      Connect(NoInfo, WRef(dp.name), firrtl.ir.UIntLiteral(BigInt(0), width))
    }

    val conns   = lhsOutBridgeClockResetConns ++
      rhsOutBridgeClockResetConns ++
      Seq(lhsInBridgeConn, rhsInBridgeConn) ++
      lhsOutBridgeConns ++
      rhsOutBridgeConns ++
      debugPortsTieDownConns
    val nodes   = Seq(lhsInPortsCat, rhsInPortsCat)
    val newBody = new Block(
      Seq(lhsBridgeInst, rhsBridgeInst) ++
        nodes ++
        conns
    )
    m.copy(body = newBody)
  }

  private def replaceModuleBodyWithCutBridge(
    cutBridgeAnnos:   mutable.ArrayBuffer[Annotation],
    cutBridgeModules: mutable.ArrayBuffer[ExtModule],
    circuitMain:      String,
    cutBridgeType:    String,
  )(m:                Module
  ): Module = {
    val bridgeInstanceName = "cut_bridge"
    val cutBridgeInfo      = generateCutBridge(circuitMain, bridgeInstanceName, m.ports, true, cutBridgeType)
    val bridgeInstance     = cutBridgeInfo.inst
    val inPorts            = cutBridgeInfo.inPorts
    val outPorts           = cutBridgeInfo.outPorts
    val outPortsBits       = cutBridgeInfo.outPortsBits
    cutBridgeAnnos.append(cutBridgeInfo.anno)
    cutBridgeModules.append(cutBridgeInfo.mod)

    // concat all the input ports & connect it to the bridge
    val inPortsCat         = DefNode(NoInfo, "cutInPortsCat", midas.passes.Utils.orderedCat(inPorts.toSeq))
    val inBridgeConnection = Connect(NoInfo, WSubField(WRef(bridgeInstance), "io_out"), WRef(inPortsCat.name))

    // slice the bridge output port & connect it to the corresponding output ports
    val sliceStartingBits    = outPortsBits.scanLeft(0)(_ + _)
    val bridgeOutPortsSliced = sliceStartingBits.zip(outPortsBits).map { case (startBit, numBits) =>
      DoPrim(
        PrimOps.Bits,
        Seq(WSubField(WRef(bridgeInstance), "io_in")),
        Seq(startBit + numBits - 1, startBit),
        firrtl.ir.UIntType(IntWidth(numBits)),
      )
    }
    val outBridgeConnections = bridgeOutPortsSliced.zip(outPorts).map { case (bo, op) =>
      Connect(NoInfo, op, bo)
    }

    // connect clock and reset to the bridge
    val bridgeClockResetConnection = m.ports.map { p =>
      val connection = p.name match {
        case "clock" => Some(Connect(NoInfo, WSubField(WRef(bridgeInstance), "clock"), WRef(p.name)))
        case "reset" => Some(Connect(NoInfo, WSubField(WRef(bridgeInstance), "reset"), WRef(p.name)))
        case _       => None
      }
      connection
    }.flatten

    // Construct a new body containing the bridge
    val connectionsToAdd = bridgeClockResetConnection ++ Seq(inBridgeConnection) ++ outBridgeConnections
    val nodesToAdd       = inPortsCat
    val newStatements    = (bridgeInstance +: (nodesToAdd +: connectionsToAdd))
    val newBody          = new Block(newStatements)
    m.copy(body = newBody)
  }

  // TODO : Apply this function to other passes as well
  private def connectBridgePorts(
    cutBridgeInfo:  CutBridgeInfo,
    inPortsCatName: String,
  ): (DefNode, Seq[Connect]) = {
    val bridgeInstance = cutBridgeInfo.inst
    val inPorts        = cutBridgeInfo.inPorts
    val outPorts       = cutBridgeInfo.outPorts
    val outPortsBits   = cutBridgeInfo.outPortsBits

    // concat all the input ports & connect it to the bridge
    val inPortsCat         = DefNode(NoInfo, inPortsCatName, midas.passes.Utils.orderedCat(inPorts.toSeq))
    val inBridgeConnection = Connect(NoInfo, WSubField(WRef(bridgeInstance), "io_out"), WRef(inPortsCat.name))

    // slice the bridge output port & connect it to the corresponding output ports
    val sliceStartingBits    = outPortsBits.scanLeft(0)(_ + _)
    val bridgeOutPortsSliced = sliceStartingBits.zip(outPortsBits).map { case (startBit, numBits) =>
      DoPrim(
        PrimOps.Bits,
        Seq(WSubField(WRef(bridgeInstance), "io_in")),
        Seq(startBit + numBits - 1, startBit),
        firrtl.ir.UIntType(IntWidth(numBits)),
      )
    }
    val outBridgeConnections = bridgeOutPortsSliced.zip(outPorts).map { case (bo, op) =>
      Connect(NoInfo, op, bo)
    }

    (inPortsCat, outBridgeConnections.toSeq :+ inBridgeConnection)
  }

  private def replaceModuleBodyWithTargetPreservingCutBridge(
    cutBridgeAnnos:   mutable.ArrayBuffer[Annotation],
    cutBridgeModules: mutable.ArrayBuffer[ExtModule],
    circuitMain:      String,
    cutBridgeType:    String,
    annos:            AnnotationSeq,
  )(m:                Module
  ): Module = {
    val combSinkPorts = annos
      .collect({ case FirrtlCombLogicInsideModuleAnno(t) =>
        t.ref
      })
      .toSet

    val srcPorts         = m.ports.filter(x => !combSinkPorts.contains(x.name))
    val srcCutBridgeInfo = generateCutBridge(circuitMain, "src_cut_bridge", srcPorts, true, cutBridgeType)

    assert(
      srcPorts.size > 0,
      """
      Module has only combinational logic between the input and output ports.
      Selected module not suitable for partitioning.
      """,
    )

    val (srcInPortsCat, srcBridgeConns) = connectBridgePorts(srcCutBridgeInfo, "srcCutInPortsCat")
    cutBridgeModules.append(srcCutBridgeInfo.mod)
    cutBridgeAnnos.append(srcCutBridgeInfo.anno)

    val srcBridgeInst        = srcCutBridgeInfo.inst
    val srcBridgeClkRstConns = m.ports.map { p =>
      val connection = p.name match {
        case "clock" => Some(Connect(NoInfo, WSubField(WRef(srcBridgeInst), "clock"), WRef(p.name)))
        case "reset" => Some(Connect(NoInfo, WSubField(WRef(srcBridgeInst), "reset"), WRef(p.name)))
        case _       => None
      }
      connection
    }.flatten

    val srcStmts = Seq(srcBridgeInst, srcInPortsCat) ++
      srcBridgeConns ++
      srcBridgeClkRstConns

    val sinkPorts = m.ports.filter(x => combSinkPorts.contains(x.name))
    val sinkStmts = if (sinkPorts.size > 0) {
      val sinkCutBridgeInfo = generateCutBridge(circuitMain, "sink_cut_bridge", sinkPorts, true, cutBridgeType)

      val (sinkInPortsCat, sinkBridgeConns) = connectBridgePorts(sinkCutBridgeInfo, "sinkCutInPortsCat")
      cutBridgeModules.append(sinkCutBridgeInfo.mod)
      cutBridgeAnnos.append(sinkCutBridgeInfo.anno)

      val sinkBridgeInst        = sinkCutBridgeInfo.inst
      val sinkBridgeClkRstConns = m.ports.map { p =>
        val connection = p.name match {
          case "clock" => Some(Connect(NoInfo, WSubField(WRef(sinkBridgeInst), "clock"), WRef(p.name)))
          case "reset" => Some(Connect(NoInfo, WSubField(WRef(sinkBridgeInst), "reset"), WRef(p.name)))
          case _       => None
        }
        connection
      }.flatten

      Seq(sinkBridgeInst, sinkInPortsCat) ++
        sinkBridgeConns ++
        sinkBridgeClkRstConns
    } else {
      Seq()
    }

    val stmts = srcStmts ++ sinkStmts
    val body  = new Block(stmts)
    m.copy(body = body)
  }

  private def removeModule(
    removeModules:  Set[String],
    performReplace: Module => Module,
  )(m:              DefModule
  ): DefModule = {
    m match {
      case mod: Module if (removeModules.contains(mod.name)) => performReplace(mod)
      case mod                                               => mod
    }
  }

  def execute(state: CircuitState): CircuitState = {
    val p                = getConfigParams(state.annotations)
    val cutBridgeType    = getCutBridgeType(p)
    val cutBridgeAnnos   = mutable.ArrayBuffer[Annotation]()
    val cutBridgeModules = mutable.ArrayBuffer[ExtModule]()
    val (groups, _)      = getGroups(state)

    val moduleBodyReplacedWithBridgeCircuit = state.circuit.map {
      val annos                               = state.annotations
      val replaceModuleBody: Module => Module = if (p(FireAxeNoCPartitionPass)) {
        replaceRingNoCModuleBodyWithCutBridges(
          cutBridgeAnnos,
          cutBridgeModules,
          state.circuit.main,
          cutBridgeType,
          annos,
        )
      } else if (p(FireAxePreserveTarget)) {
        replaceModuleBodyWithTargetPreservingCutBridge(
          cutBridgeAnnos,
          cutBridgeModules,
          state.circuit.main,
          cutBridgeType,
          annos,
        )
      } else {
        replaceModuleBodyWithCutBridge(cutBridgeAnnos, cutBridgeModules, state.circuit.main, cutBridgeType)
      }
      removeModule(groups.toSet, replaceModuleBody)
    }

    val moduleBodyReplacedState = state.copy(
      circuit     = moduleBodyReplacedWithBridgeCircuit.copy(
        modules = moduleBodyReplacedWithBridgeCircuit.modules ++ cutBridgeModules.toSeq
      ),
      annotations = state.annotations ++ cutBridgeAnnos.toSeq,
    )
    moduleBodyReplacedState
  }
}

class ModifyTargetBoundaryForRemovePass
    extends Transform
    with DependencyAPIMigration
    with SkidBufferInsertionPass
    with MakeRoCCBusyBitLatencyInsensitivePass {

  import PartitionModulesInfo._

  def transformTargetBoundary(state: CircuitState): CircuitState = {
    val (_, groupWrappers)      = getGroups(state)
    val skidBufferInsertedState = groupWrappers.foldLeft(state) { (s, wm) =>
      insertSkidBuffersToWrapper(s, wm, insertForIncoming = false)
    }

    val roccBusyBitLatencyInsensitiveState = groupWrappers.foldLeft(skidBufferInsertedState) { (s, wm) =>
      replaceIncomingBusyWithRoCCFired(s, wm)
    }
    roccBusyBitLatencyInsensitiveState
  }

  def execute(state: CircuitState): CircuitState = {
    val p              = getConfigParams(state.annotations)
    val preserveTarget = p(FireAxePreserveTarget)
    println(s"PreserveTarget? ${preserveTarget}")
    if (preserveTarget) {
      state
    } else {
      transformTargetBoundary(state)
    }
  }
}

class PruneUnrelatedAnnoPass extends Transform with DependencyAPIMigration {

  private def prune(state: CircuitState): CircuitState = {
    val igraph = InstanceKeyGraph(state.circuit)
    val annos  = state.annotations

    val prunedAnnos = annos.filter(a =>
      a match {
        case InternalAutoCounterFirrtlAnnotation(target, _, _, _, _, _, _) =>
          if (igraph.findInstancesInHierarchy(target.module).size > 0) true else false
        case AutoCounterCoverModuleFirrtlAnnotation(target)                =>
          if (igraph.findInstancesInHierarchy(target.module).size > 0) true else false
        case InternalTriggerSinkAnnotation(target, _)                      =>
          if (igraph.findInstancesInHierarchy(target.module).size > 0) true else false
        case InternalTriggerSourceAnnotation(target, _, _, _)              =>
          if (igraph.findInstancesInHierarchy(target.module).size > 0) true else false
        case _                                                             => true
      }
    )
    state.copy(annotations = prunedAnnos)
  }

  def execute(state: CircuitState): CircuitState = {
    prune(state)
  }
}
