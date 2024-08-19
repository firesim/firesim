package midas.passes.partition

import scala.collection.mutable
import scala.Console.println

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.annotations._
import firrtl.stage.OutputFileAnnotation
import firrtl.analyses.InstanceGraph

import midas.stage._
import midas.targetutils._
import midas._

import firesim.lib.bridges.{ClockParameters, PeekPokeKey, ResetPulseBridgeParameters}
import firesim.lib.bridgeutils._
import firesim.lib.bridgeutils.SerializationUtils._

// Extracts a module from the hierarchy, and uses that as the top level target module.
// Generates a FireSim wrapper and adds on clock, reset, peekpoke and a cut bridge.
object ExtractModulesPrunePassInfo {
  var CIRCUIT_MAIN: String                 = ""
  var CUTBRIDGE_ANNOS: Seq[Annotation]     = Seq()
  var DEFAULTBRIDGE_ANNOS: Seq[Annotation] = Seq()
}

class GenerateFireSimWrapper extends Transform with DependencyAPIMigration with GenCutBridgePass {
  import PartitionModulesInfo._
  import ExtractModulesPrunePassInfo._

  protected val bridgeModulesToAdd = mutable.ArrayBuffer[ExtModule]()

  def genBridgeModuleAndInstance(
    instanceName: String,
    moduleName:   String,
    ports:        Seq[Port],
  ): DefInstance = {
    val addedModuleName = moduleName + "Added"
    bridgeModulesToAdd.append(ExtModule(NoInfo, addedModuleName, ports, addedModuleName, Seq()))
    DefInstance(instanceName, addedModuleName)
  }

  def unpackPortExpr(e: Expression, base: Expression): Expression = {
    val exp = e match {
      case WRef(n, _, _, _)      => WSubField(base, n)
      case WSubField(e, n, _, _) =>
        val parentUnpacked = unpackPortExpr(e, base)
        WSubField(parentUnpacked, n)
    }
    exp
  }

  def unpackPortExprs(es: Seq[Expression], base: Expression): Seq[Expression] = {
    es.map(unpackPortExpr(_, base))
  }

  def genDefaultBridges(): (DefInstance, DefInstance, DefInstance) = {
    val peekPokeBridge = genBridgeModuleAndInstance(
      instanceName = "extractPeekPokeBridge",
      moduleName   = "PeekPokeBridge",
      ports        =
        Seq(Port(NoInfo, "clock", Input, ClockType), Port(NoInfo, "reset", Output, firrtl.ir.UIntType(IntWidth(1)))),
    )

    val resetPulseBridge = genBridgeModuleAndInstance(
      instanceName = "extractResetBridge",
      moduleName   = "ResetPulseBridge",
      ports        =
        Seq(Port(NoInfo, "clock", Input, ClockType), Port(NoInfo, "reset", Output, firrtl.ir.UIntType(IntWidth(1)))),
    )

    val clockBridge = genBridgeModuleAndInstance(
      instanceName = "extractClockBridge",
      moduleName   = "RationalClockBridge",
      ports        = Seq(Port(NoInfo, "clocks_0", Output, ClockType)),
    ) // FIXME : currently only support a single clock domain for the extracted module

    (peekPokeBridge, resetPulseBridge, clockBridge)
  }

  def genDefaultBridgeAnnos(circuitMain: String): Seq[BridgeAnnotation] = {
    val clockBridgeMT   = ModuleTarget(circuitMain, "RationalClockBridgeAdded")
    val rationalClock   = RationalClock("baseClock", 1, 1)
    val clockBridgeAnno = BridgeAnnotation(
      target               = clockBridgeMT,
      bridgeChannels       = Seq(
        ClockBridgeChannel(
          name       = "clocks",
          sinks      = Seq(ReferenceTarget(circuitMain, "RationalClockBridge", Seq(), "clocks_0", Seq())),
          clocks     = Seq(rationalClock),
          clockMFMRs = Seq(1),
        )
      ),
      widgetClass          = "midas.widgets.ClockBridgeModule",
      widgetConstructorKey = Some(ClockParameters(Seq(rationalClock))),
    )

    val peekPokeBridgeMT   = ModuleTarget(circuitMain, "PeekPokeBridgeAdded")
    val peekPokeBridgeAnno = BridgeAnnotation(
      target               = peekPokeBridgeMT,
      bridgeChannels       = Seq(
        PipeBridgeChannel(
          name    = "reset",
          clock   = peekPokeBridgeMT.ref("clock"),
          sinks   = Seq(peekPokeBridgeMT.ref("reset")),
          sources = Seq(),
          latency = 0,
        )
      ),
      widgetClass          = "midas.widgets.PeekPokeBridgeModule",
      widgetConstructorKey = Some(
        PeekPokeKey(
          peeks                = Seq(),
          pokes                = Seq(SerializableField("reset", SerializationUtils.UIntType, 1)),
          maxChannelDecoupling = 2,
        )
      ),
    )

    val resetPulseBridgeMT   = ModuleTarget(circuitMain, "ResetPulseBridgeAdded")
    val resetPulseBridgeAnno = BridgeAnnotation(
      target               = resetPulseBridgeMT,
      bridgeChannels       = Seq(
        PipeBridgeChannel(
          name    = "reset",
          clock   = peekPokeBridgeMT.ref("clock"),
          sinks   = Seq(peekPokeBridgeMT.ref("reset")),
          sources = Seq(),
          latency = 0,
        )
      ),
      widgetClass          = "midas.widgets.ResetPulseBridgeModule",
      widgetConstructorKey = Some(ResetPulseBridgeParameters()),
    )
    Seq(clockBridgeAnno, peekPokeBridgeAnno, resetPulseBridgeAnno)
  }

  def genFireSimWrapperBodyForNoC(
    circuitMain:      String,
    m:                DefModule,
    annos:            AnnotationSeq,
    cutBridgeModules: mutable.ArrayBuffer[ExtModule],
    cutBridgeAnnos:   mutable.ArrayBuffer[Annotation],
  ): Statement = {
    val (peekPokeBridge, resetPulseBridge, clockBridge) = genDefaultBridges()
    val extractedModule                                 = DefInstance(name = extractModuleInstanceName, module = m.name)
    val buildtopReset                                   = DefWire(info = NoInfo, name = "buildtopReset", tpe = ResetType)
    val buildtopClock                                   = DefWire(info = NoInfo, name = "buildtopClock", tpe = ClockType)
    val buildtopResetConnection                         =
      Connect(NoInfo, WRef(buildtopReset.name), WSubField(WRef(resetPulseBridge.name), "reset"))
    val clockBridgeClocks                               = WSubField(WRef(clockBridge.name), "clocks_0")

    val buildtopClockConnection       = Connect(NoInfo, WRef(buildtopClock.name), clockBridgeClocks)
    val peekPokeBridgeClockConnection =
      Connect(NoInfo, WSubField(WRef(peekPokeBridge.name), "clock"), WRef(buildtopClock))
    val resetBridgeClockConnection    =
      Connect(NoInfo, WSubField(WRef(resetPulseBridge.name), "clock"), WRef(buildtopClock))
    val extractModuleClockConnection  =
      Connect(NoInfo, WSubField(WRef(extractedModule.name), "clock"), WRef(buildtopClock))

    val portNameToGroupIdxMap = annos
      .flatMap(anno =>
        anno match {
          case FirrtlPortToNeighborRouterIdxAnno(rt, extractIdx, _) =>
            Some(rt.ref -> extractIdx)
          case _                                                    => None
        }
      )
      .toMap
    val ports                 = m.ports
    ports.foreach(port => println(s"extractedModule port ${port.name}"))

    val p               = annos
      .collectFirst({ case midas.stage.phases.ConfigParametersAnnotation(p) =>
        p
      })
      .get
    val nGroups         = p(FireAxePartitionGlobalInfo).get.size
    val groupIdxToPorts = mutable.Map[Int, mutable.Set[String]]()
    portNameToGroupIdxMap.foreach { case (pn, gidx) =>
      if (!groupIdxToPorts.contains(gidx)) groupIdxToPorts(gidx) = mutable.Set[String]()
      groupIdxToPorts(gidx).add(pn)
    }

    val curGroupIdx = p(FireAxePartitionIndex) match {
      case Some(idx) => idx
      case None      => nGroups - 1
    }
    val rhsGroupIdx = (curGroupIdx + 1)           % nGroups
    val lhsGroupIdx = (curGroupIdx + nGroups - 1) % nGroups

    println(s"groupIdxToPorts ${groupIdxToPorts}")
    println(s"lhsGroupIdx ${lhsGroupIdx} curGroupIdx ${curGroupIdx} rhsGroupIdx ${rhsGroupIdx}")

    // FIXME : how should be properly propagate the reset signal ??????

    val extractModuleResetConnection =
      Connect(NoInfo, WSubField(WRef(extractedModule.name), "reset"), WRef(buildtopReset))

    val lhsPortNames = groupIdxToPorts(lhsGroupIdx)
    val rhsPortNames = groupIdxToPorts(rhsGroupIdx)
    val lhsPorts     = ports.filter { port => lhsPortNames.contains(port.name) }
    val rhsPorts     = ports.filter { port => rhsPortNames.contains(port.name) }

    println(s"lhsPorts ${lhsPorts}")
    println(s"rhsPorts ${rhsPorts}")

    // FIXME : Dedup lhs rhs cut bridge module body generation
    // lhs bridge
    val cutBridgeType    = getCutBridgeType(p)
    val lhsCutBridgeInfo = generateCutBridge(circuitMain, "lhsCutBridge", lhsPorts, inModule = false, cutBridgeType)
    cutBridgeModules.append(lhsCutBridgeInfo.mod)
    cutBridgeAnnos.append(lhsCutBridgeInfo.anno)

    val lhsCutBridgeInst = lhsCutBridgeInfo.inst
    val lhsInPorts       = lhsCutBridgeInfo.inPorts
    val lhsInPortsBits   = lhsCutBridgeInfo.inPortsBits
    val lhsOutPorts      = lhsCutBridgeInfo.outPorts

    val lhsOutPortsModuleRef = lhsOutPorts.map { op => unpackPortExpr(op, WRef(extractedModule)) }
    val lhsInPortsModuleRef  = lhsInPorts.map { ip => unpackPortExpr(ip, WRef(extractedModule)) }

    val lhsOutPortsCat              =
      DefNode(info = NoInfo, name = "lhsCutOutPortsCat", midas.passes.Utils.orderedCat(lhsOutPortsModuleRef.toSeq))
    val lhsOutBridgeConnection      =
      Connect(info = NoInfo, WSubField(WRef(lhsCutBridgeInst), "io_out"), WRef(lhsOutPortsCat.name))

    val lhsSliceStartingBits        = lhsInPortsBits.scanLeft(0)(_ + _)
    val lhsBridgeOutPortsSliced     = lhsSliceStartingBits.zip(lhsInPortsBits).map { case (startBit, numBits) =>
      DoPrim(
        PrimOps.Bits,
        Seq(WSubField(WRef(lhsCutBridgeInst), "io_in")),
        Seq(startBit + numBits - 1, startBit),
        firrtl.ir.UIntType(IntWidth(numBits)),
      )
    }
    val lhsInBridgeConnections      = lhsBridgeOutPortsSliced.zip(lhsInPortsModuleRef).map { case (bo, ip) =>
      Connect(NoInfo, ip, bo)
    }
    val lhsCutBridgeClockConnection = Connect(NoInfo, WSubField(WRef(lhsCutBridgeInst), "clock"), WRef(buildtopClock))
    val lhsCutBridgeResetConnection = Connect(NoInfo, WSubField(WRef(lhsCutBridgeInst), "reset"), WRef(buildtopReset))

    // rhs bridge
    val rhsCutBridgeInfo = generateCutBridge(circuitMain, "rhsCutBridge", rhsPorts, inModule = false, cutBridgeType)
    cutBridgeModules.append(rhsCutBridgeInfo.mod)
    cutBridgeAnnos.append(rhsCutBridgeInfo.anno)

    val rhsCutBridgeInst = rhsCutBridgeInfo.inst
    val rhsInPorts       = rhsCutBridgeInfo.inPorts
    val rhsInPortsBits   = rhsCutBridgeInfo.inPortsBits
    val rhsOutPorts      = rhsCutBridgeInfo.outPorts

    val rhsOutPortsModuleRef = rhsOutPorts.map { op => unpackPortExpr(op, WRef(extractedModule)) }
    val rhsInPortsModuleRef  = rhsInPorts.map { ip => unpackPortExpr(ip, WRef(extractedModule)) }

    val rhsOutPortsCat              =
      DefNode(info = NoInfo, name = "rhsCutOutPortsCat", midas.passes.Utils.orderedCat(rhsOutPortsModuleRef.toSeq))
    val rhsOutBridgeConnection      =
      Connect(info = NoInfo, WSubField(WRef(rhsCutBridgeInst), "io_out"), WRef(rhsOutPortsCat.name))

    val rhsSliceStartingBits        = rhsInPortsBits.scanLeft(0)(_ + _)
    val rhsBridgeOutPortsSliced     = rhsSliceStartingBits.zip(rhsInPortsBits).map { case (startBit, numBits) =>
      DoPrim(
        PrimOps.Bits,
        Seq(WSubField(WRef(rhsCutBridgeInst), "io_in")),
        Seq(startBit + numBits - 1, startBit),
        firrtl.ir.UIntType(IntWidth(numBits)),
      )
    }
    val rhsInBridgeConnections      = rhsBridgeOutPortsSliced.zip(rhsInPortsModuleRef).map { case (bo, ip) =>
      Connect(NoInfo, ip, bo)
    }
    val rhsCutBridgeClockConnection = Connect(NoInfo, WSubField(WRef(rhsCutBridgeInst), "clock"), WRef(buildtopClock))
    val rhsCutBridgeResetConnection = Connect(NoInfo, WSubField(WRef(rhsCutBridgeInst), "reset"), WRef(buildtopReset))

    val instances = Seq(
      peekPokeBridge,
      resetPulseBridge,
      clockBridge,
      extractedModule,
      buildtopReset,
      buildtopClock,
      lhsCutBridgeInst,
      lhsOutPortsCat,
      rhsCutBridgeInst,
      rhsOutPortsCat,
    )

    val connections = Seq(
      buildtopResetConnection,
      buildtopClockConnection,
      peekPokeBridgeClockConnection,
      resetBridgeClockConnection,
      extractModuleClockConnection,
      extractModuleResetConnection,
      lhsOutBridgeConnection,
      lhsCutBridgeClockConnection,
      lhsCutBridgeResetConnection,
      rhsOutBridgeConnection,
      rhsCutBridgeClockConnection,
      rhsCutBridgeResetConnection,
    ) ++
      lhsInBridgeConnections ++
      rhsInBridgeConnections

    val statements = instances ++ connections
    val body       = new Block(statements)
    body
  }

  def genFireSimWrapperBody(
    circuitMain:      String,
    m:                DefModule,
    annos:            AnnotationSeq,
    cutBridgeModules: mutable.ArrayBuffer[ExtModule],
    cutBridgeAnnos:   mutable.ArrayBuffer[Annotation],
  ): Statement = {
    val (peekPokeBridge, resetPulseBridge, clockBridge) = genDefaultBridges()
    val extractedModule                                 = DefInstance(name = extractModuleInstanceName, module = m.name)
    val buildtopReset                                   = DefWire(info = NoInfo, name = "buildtopReset", tpe = ResetType)
    val buildtopClock                                   = DefWire(info = NoInfo, name = "buildtopClock", tpe = ClockType)
    val buildtopResetConnection                         =
      Connect(NoInfo, WRef(buildtopReset.name), WSubField(WRef(resetPulseBridge.name), "reset"))
    val clockBridgeClocks                               = WSubField(WRef(clockBridge.name), "clocks_0")

    val buildtopClockConnection       = Connect(NoInfo, WRef(buildtopClock.name), clockBridgeClocks)
    val peekPokeBridgeClockConnection =
      Connect(NoInfo, WSubField(WRef(peekPokeBridge.name), "clock"), WRef(buildtopClock))
    val resetBridgeClockConnection    =
      Connect(NoInfo, WSubField(WRef(resetPulseBridge.name), "clock"), WRef(buildtopClock))
    val extractModuleClockConnection  =
      Connect(NoInfo, WSubField(WRef(extractedModule.name), "clock"), WRef(buildtopClock))

    val p             = annos
      .collectFirst({ case midas.stage.phases.ConfigParametersAnnotation(p) =>
        p
      })
      .get
    val cutBridgeType = getCutBridgeType(p)
    val cutBridgeInfo = generateCutBridge(circuitMain, "cut_bridge", m.ports, inModule = false, cutBridgeType)
    val cutBridgeInst = cutBridgeInfo.inst
    val inPorts       = cutBridgeInfo.inPorts
    val inPortsBits   = cutBridgeInfo.inPortsBits
    val outPorts      = cutBridgeInfo.outPorts
    cutBridgeModules.append(cutBridgeInfo.mod)
    cutBridgeAnnos.append(cutBridgeInfo.anno)

    // Add module to the front of the expression to access the IO ports
    val outPortsModuleRef = outPorts.map { op => unpackPortExpr(op, WRef(extractedModule)) }
    val inPortsModuleRef  = inPorts.map { ip => unpackPortExpr(ip, WRef(extractedModule)) }

    // concat all the output ports & connect it to the bridge
    val outPortsCat         =
      DefNode(info = NoInfo, name = "cutOutPortsCat", midas.passes.Utils.orderedCat(outPortsModuleRef.toSeq))
    val outBridgeConnection = Connect(info = NoInfo, WSubField(WRef(cutBridgeInst), "io_out"), WRef(outPortsCat.name))

    // slice the bridge output port & connect it to the corresponding input ports
    val sliceStartingBits    = inPortsBits.scanLeft(0)(_ + _)
    val bridgeOutPortsSliced = sliceStartingBits.zip(inPortsBits).map { case (startBit, numBits) =>
      DoPrim(
        PrimOps.Bits,
        Seq(WSubField(WRef(cutBridgeInst), "io_in")),
        Seq(startBit + numBits - 1, startBit),
        firrtl.ir.UIntType(IntWidth(numBits)),
      )
    }
    val inBridgeConnections  = bridgeOutPortsSliced.zip(inPortsModuleRef).map { case (bo, ip) =>
      Connect(NoInfo, ip, bo)
    }

    val cutBridgeClockConnection = Connect(NoInfo, WSubField(WRef(cutBridgeInst), "clock"), WRef(buildtopClock))
    val cutBridgeResetConnection = Connect(NoInfo, WSubField(WRef(cutBridgeInst), "reset"), WRef(buildtopReset))

    val connections = Seq(
      buildtopResetConnection,
      buildtopClockConnection,
      peekPokeBridgeClockConnection,
      resetBridgeClockConnection,
      extractModuleClockConnection,
      outBridgeConnection,
      cutBridgeClockConnection,
      cutBridgeResetConnection,
    ) ++
      inBridgeConnections

    val instances = Seq(
      peekPokeBridge,
      resetPulseBridge,
      clockBridge,
      extractedModule,
      buildtopReset,
      buildtopClock,
      cutBridgeInst,
      outPortsCat,
    )

    val statements = instances ++ connections
    val body       = new Block(statements)
    body
  }
  // TODO : Apply this function to other passes as well
  def connectBridgePorts(
    extractedModule: DefInstance,
    cutBridgeInfo:   CutBridgeInfo,
    outPortsCatName: String,
  ): (DefNode, Seq[Connect]) = {
    val cutBridgeInst = cutBridgeInfo.inst
    val inPorts       = cutBridgeInfo.inPorts
    val inPortsBits   = cutBridgeInfo.inPortsBits
    val outPorts      = cutBridgeInfo.outPorts

    // Add module to the front of the expression to access the IO ports
    val outPortsModuleRef = outPorts.map { op => unpackPortExpr(op, WRef(extractedModule)) }
    val inPortsModuleRef  = inPorts.map { ip => unpackPortExpr(ip, WRef(extractedModule)) }

    // concat all the output ports & connect it to the bridge
    val outPortsCat         = DefNode(NoInfo, outPortsCatName, midas.passes.Utils.orderedCat(outPortsModuleRef.toSeq))
    val outBridgeConnection = Connect(info = NoInfo, WSubField(WRef(cutBridgeInst), "io_out"), WRef(outPortsCat.name))

    // slice the bridge output port & connect it to the corresponding input ports
    val sliceStartingBits    = inPortsBits.scanLeft(0)(_ + _)
    val bridgeOutPortsSliced = sliceStartingBits.zip(inPortsBits).map { case (startBit, numBits) =>
      DoPrim(
        PrimOps.Bits,
        Seq(WSubField(WRef(cutBridgeInst), "io_in")),
        Seq(startBit + numBits - 1, startBit),
        firrtl.ir.UIntType(IntWidth(numBits)),
      )
    }
    val inBridgeConnections  = bridgeOutPortsSliced
      .zip(inPortsModuleRef)
      .map { case (bo, ip) =>
        Connect(NoInfo, ip, bo)
      }
      .toSeq

    (outPortsCat, inBridgeConnections :+ outBridgeConnection)
  }

  def genFireSimWrapperBodyTargetPreserving(
    circuitMain:      String,
    m:                DefModule,
    annos:            AnnotationSeq,
    cutBridgeModules: mutable.ArrayBuffer[ExtModule],
    cutBridgeAnnos:   mutable.ArrayBuffer[Annotation],
  ): Statement = {
    val (peekPokeBridge, resetPulseBridge, clockBridge) = genDefaultBridges()
    val extractedModule                                 = DefInstance(name = extractModuleInstanceName, module = m.name)
    val buildtopReset                                   = DefWire(info = NoInfo, name = "buildtopReset", tpe = ResetType)
    val buildtopClock                                   = DefWire(info = NoInfo, name = "buildtopClock", tpe = ClockType)
    val buildtopResetConnection                         =
      Connect(NoInfo, WRef(buildtopReset.name), WSubField(WRef(resetPulseBridge.name), "reset"))
    val clockBridgeClocks                               = WSubField(WRef(clockBridge.name), "clocks_0")

    val buildtopClockConnection       = Connect(NoInfo, WRef(buildtopClock.name), clockBridgeClocks)
    val peekPokeBridgeClockConnection =
      Connect(NoInfo, WSubField(WRef(peekPokeBridge.name), "clock"), WRef(buildtopClock))
    val resetBridgeClockConnection    =
      Connect(NoInfo, WSubField(WRef(resetPulseBridge.name), "clock"), WRef(buildtopClock))
    val extractModuleClockConnection  =
      Connect(NoInfo, WSubField(WRef(extractedModule.name), "clock"), WRef(buildtopClock))

    val p             = annos
      .collectFirst({ case midas.stage.phases.ConfigParametersAnnotation(p) =>
        p
      })
      .get
    val cutBridgeType = getCutBridgeType(p)

    // Collect all the input & output port names that have combinational
    // dependencies between then within this module
    val combSinkPorts = annos
      .collect({ case FirrtlCombLogicInsideModuleAnno(t) =>
        t.ref
      })
      .toSet

    val srcPorts  = m.ports.filter(x => !combSinkPorts.contains(x.name))
    val sinkPorts = m.ports.filter(x => combSinkPorts.contains(x.name))

    srcPorts.foreach { p => println(s"srcPort ${p.name}") }
    sinkPorts.foreach { p => println(s"sinkPort ${p.name}") }

    assert(
      srcPorts.size > 0,
      """
      Module has only combinational logic between the input and output ports.
      Selected module not suitable for partitioning.
      """,
    )

    val srcCutBridgeInfo = generateCutBridge(circuitMain, "srcCutBridge", srcPorts, false, cutBridgeType)

    cutBridgeModules.append(srcCutBridgeInfo.mod)
    cutBridgeAnnos.append(srcCutBridgeInfo.anno)

    val (srcOutPortsCat, srcBridgeConns) = connectBridgePorts(extractedModule, srcCutBridgeInfo, "srcOutPortsCat")
    val srcBridgeInst                    = srcCutBridgeInfo.inst
    val srcBridgeClockConnection         = Connect(NoInfo, WSubField(WRef(srcBridgeInst), "clock"), WRef(buildtopClock))
    val srcBridgeResetConnection         = Connect(NoInfo, WSubField(WRef(srcBridgeInst), "reset"), WRef(buildtopReset))

    val sinkBridgeStmts = if (sinkPorts.size > 0) {
      val sinkCutBridgeInfo = generateCutBridge(circuitMain, "sinkCutBridge", sinkPorts, false, cutBridgeType)

      cutBridgeModules.append(sinkCutBridgeInfo.mod)
      cutBridgeAnnos.append(sinkCutBridgeInfo.anno)

      val (sinkOutPortsCat, sinkBridgeConns) = connectBridgePorts(extractedModule, sinkCutBridgeInfo, "sinkOutPortsCat")
      val sbi                                = sinkCutBridgeInfo.inst
      val sbcc                               = Connect(NoInfo, WSubField(WRef(sbi), "clock"), WRef(buildtopClock))
      val sbrc                               = Connect(NoInfo, WSubField(WRef(sbi), "reset"), WRef(buildtopReset))
      Seq(sbi, sinkOutPortsCat, sbcc, sbrc) ++ sinkBridgeConns
    } else {
      Seq()
    }

    val conns = Seq(
      buildtopResetConnection,
      buildtopClockConnection,
      peekPokeBridgeClockConnection,
      resetBridgeClockConnection,
      extractModuleClockConnection,
      srcBridgeClockConnection,
      srcBridgeResetConnection,
    ) ++
      srcBridgeConns

    val insts = Seq(
      peekPokeBridge,
      resetPulseBridge,
      clockBridge,
      extractedModule,
      buildtopReset,
      buildtopClock,
      srcBridgeInst,
      srcOutPortsCat,
    )

    val stmts = insts ++ conns ++ sinkBridgeStmts
    new Block(stmts)
  }

  def genFireSimWrapper(
    circuitMain:      String,
    m:                DefModule,
    annos:            AnnotationSeq,
    cutBridgeModules: mutable.ArrayBuffer[ExtModule],
    cutBridgeAnnos:   mutable.ArrayBuffer[Annotation],
  ): DefModule = {
    val p           = annos
      .collectFirst({ case midas.stage.phases.ConfigParametersAnnotation(p) =>
        p
      })
      .get
    val wrapperBody = if (p(FireAxeNoCPartitionPass)) {
      genFireSimWrapperBodyForNoC(circuitMain, m, annos, cutBridgeModules, cutBridgeAnnos)
    } else if (p(FireAxePreserveTarget)) {
      genFireSimWrapperBodyTargetPreserving(circuitMain, m, annos, cutBridgeModules, cutBridgeAnnos)
    } else {
      genFireSimWrapperBody(circuitMain, m, annos, cutBridgeModules, cutBridgeAnnos)
    }
    val wrapper     = new Module(NoInfo, fireSimWrapper, Seq(), wrapperBody)
    wrapper
  }

  def execute(state: CircuitState): CircuitState = {
    val p       = getConfigParams(state.annotations)
    val partIdx = p(FireAxePartitionIndex).getOrElse(0)

    // When performing the extract pass for a NoC, we only have one group
    val extractModuleGroupIdx = if (p(FireAxeNoCPartitionPass)) 0 else partIdx
    val extractModuleName     = getGroupName(extractModuleGroupIdx)
    println(s"extractModuleName ${extractModuleName}")

    val cutBridgeAnnos   = mutable.ArrayBuffer[Annotation]()
    val cutBridgeModules = mutable.ArrayBuffer[ExtModule]()
    val moduleList       = state.circuit.modules.flatMap {
      case m: Module if (m.name == extractModuleName) =>
        val wrapper = genFireSimWrapper(state.circuit.main, m, state.annotations, cutBridgeModules, cutBridgeAnnos)
        Seq(m, wrapper) ++ cutBridgeModules.toSeq ++ bridgeModulesToAdd.toSeq
      case m                                          => Seq(m)
    }

    // Alternative of using annotations to pass info to the downstream passes
    CIRCUIT_MAIN        = state.circuit.main
    CUTBRIDGE_ANNOS     = cutBridgeAnnos.toSeq
    DEFAULTBRIDGE_ANNOS = genDefaultBridgeAnnos(state.circuit.main)

    val fireSimWrapperAsTopCircuit = state.circuit.copy(modules = moduleList, main = fireSimWrapper)
    val fireSimWrapperAsTopState   = state.copy(circuit = fireSimWrapperAsTopCircuit)
    fireSimWrapperAsTopState
  }
}

class PrunedExtraModulesAndAddBridgeAnnos extends Transform with DependencyAPIMigration {
  import ExtractModulesPrunePassInfo._
  import PartitionModulesInfo._

  def collectSubModules(
    curModule:  String,
    subModules: mutable.Map[String, mutable.Set[String]],
  )(s:          Statement
  ): Statement = {
    val visited = s.map(collectSubModules(curModule, subModules)(_))
    visited match {
      case DefInstance(_, _, module, _) =>
        subModules(curModule).add(module)
      case _                            => ()
    }
    s
  }

  def collectSubModulesUnderDefModule(
    subModules: mutable.Map[String, mutable.Set[String]]
  )(m:          DefModule
  ): DefModule = {
    val curModuleName = m.name
    subModules(curModuleName) = mutable.Set[String]()
    m.map(collectSubModules(curModuleName, subModules)(_))
    m
  }

  def collectValidModulesAfterPartition(
    cur:       String,
    childsMap: mutable.Map[String, mutable.Set[String]],
    valids:    mutable.Set[String],
  ): Unit = {
    valids.add(cur)
    childsMap(cur).foreach(c => collectValidModulesAfterPartition(c, childsMap, valids))
  }

  def traverseNewHierarchyAndCollectValidModules(valids: mutable.Set[String], circuit: Circuit) = {
    val subModules = mutable.Map[String, mutable.Set[String]]()
    circuit.map(collectSubModulesUnderDefModule(subModules)(_))
    collectValidModulesAfterPartition(circuit.main, subModules, valids)
  }

  def renameCircuitMain(
    circuitMain: String,
    newModules:  mutable.Set[DefModule],
  )(m:           DefModule
  ): DefModule = {
    if (m.name == fireSimWrapper) {
      newModules.add(new Module(NoInfo, circuitMain, Seq(), m.asInstanceOf[Module].body))
    } else if (m.name != circuitMain) {
      newModules.add(m)
    }
    m
  }

  def removeAllExtraneousModulesFromHierarchy(
    circuit:                    Circuit,
    originalCircuitMain:        String,
    validModulesAfterPartition: mutable.Set[String],
  ): Circuit = {
    // 1. Collect valid module-names by traversing the new hierarchy
    traverseNewHierarchyAndCollectValidModules(validModulesAfterPartition, circuit)

    // 2. Rename the top module from fireSimWrapper to its original name(usually FireSim)
    val newModuleDefs = mutable.Set[DefModule]()
    circuit.map(renameCircuitMain(originalCircuitMain, newModuleDefs)(_))

    // 3. remove all the extraneous modules from the hierarchy
    val prunedValidModuleDefs = newModuleDefs.filter { m =>
      validModulesAfterPartition.contains(m.name) || (m.name == originalCircuitMain)
    }

    circuit.copy(modules = prunedValidModuleDefs.toSeq, main = originalCircuitMain)
  }

  def collectValidAnnosAfterPartition(
    annos:        AnnotationSeq,
    validAnnos:   mutable.Set[Annotation],
    validModules: mutable.Set[String],
  ): Unit = {
    annos.foreach { anno =>
      val targets = anno.getTargets
      if (targets.length == 0) {
        validAnnos.add(anno)
      } else {
        val isValid = targets
          .map { t =>
            t.moduleOpt match {
              case Some(name) if (validModules.contains(name)) => true
              case Some(_)                                     => false
              case None                                        => true
            }
          }
          .reduce(_ && _)
        if (isValid) validAnnos.add(anno)
      }
    }
  }

  // HACK...
  def collectExtraAnnos(state: CircuitState): Seq[Annotation] = {
    val p = state.annotations
      .collectFirst({ case midas.stage.phases.ConfigParametersAnnotation(p) =>
        p
      })
      .get

    val outputBaseFileNameAnno = state.annotations.collectFirst { case _: OutputBaseFilenameAnnotation =>
      OutputBaseFilenameAnnotation("FireSim-generated")
    }.get

    val outputFileAnnotation = state.annotations.collectFirst { case a: OutputFileAnnotation =>
      a
    }.get

    val targetDirAnno = state.annotations.collectFirst { case a: TargetDirAnnotation =>
      a
    }.get

    val emitCircuitAnno = state.annotations.collectFirst { case a: EmitCircuitAnnotation =>
      a
    }.get

    val annos = Seq(
      midas.stage.phases.ConfigParametersAnnotation(p),
      targetDirAnno,
      outputBaseFileNameAnno,
      outputFileAnnotation,
      emitCircuitAnno,
    )
    annos
  }

  def pruneState(
    unprunedState:   CircuitState,
    origCircuitMain: String,
  ): CircuitState = {
    val validModulesAfterPartition = mutable.Set[String]()
    val prunedCircuit              =
      removeAllExtraneousModulesFromHierarchy(unprunedState.circuit, origCircuitMain, validModulesAfterPartition)

    val validAnnosAfterPartition = mutable.Set[Annotation]()
    collectValidAnnosAfterPartition(unprunedState.annotations, validAnnosAfterPartition, validModulesAfterPartition)

    val extraAnnos  = collectExtraAnnos(unprunedState)
    val prunedAnnos = validAnnosAfterPartition.toSeq ++ extraAnnos
    unprunedState.copy(circuit = prunedCircuit, annotations = prunedAnnos)
  }

  def addBridgeAnnos(state: CircuitState): CircuitState = {
    val annos = state.annotations ++ CUTBRIDGE_ANNOS ++ DEFAULTBRIDGE_ANNOS
    state.copy(annotations = annos)
  }

  def execute(state: CircuitState): CircuitState = {
    addBridgeAnnos(pruneState(state, CIRCUIT_MAIN))
  }
}

class ModifyTargetBoundaryForExtractPass extends Transform with DependencyAPIMigration with SkidBufferInsertionPass {

  import PartitionModulesInfo._

  def transformTargetBoundary(state: CircuitState): CircuitState = {
    val p                     = getConfigParams(state.annotations)
    val extractModuleNames    = state.annotations
      .collectFirst(_ match {
        case ExtractModuleNameAnnotation(names) => Seq(names)
      })
      .getOrElse(p(FireAxePartitionGlobalInfo).get.flatten)
    val extractModuleWrappers = extractModuleNames.map(wrapperPfx + "_" + _)

    println(s"extractModuleNames_2 ${extractModuleNames}")

    val skidBufferInsertedState = extractModuleWrappers.foldLeft(state) { (st, emn) =>
      insertSkidBuffersToWrapper(st, emn, insertForIncoming = true)
    }
    skidBufferInsertedState
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
