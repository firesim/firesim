// See LICENSE for license details.

package midas.passes

import firrtl._
import firrtl.ir._
import firrtl.annotations._
import firrtl.annotations.TargetToken._
import midas.widgets._
import midas.targetutils._
import midas.passes.fame.{WireChannel, FAMEChannelConnectionAnnotation}

import java.io._
import collection.mutable
import firrtl.analyses.InstanceKeyGraph

/**
  * Take the annotated target and drive with plus args bridge(s)
  */
object PlusArgsWiringTransform extends Transform {
  def inputForm: CircuitForm = LowForm
  def outputForm: CircuitForm = MidForm
  override def name = "[Golden Gate] PlusArgs Wiring Transform"

  private var idx = 0
  def getPlusArgBridgeName(argName: String): String = {
    val trimmedName = argName.replace("=%d", "")
    val ret = s"PlusArgBridge_${trimmedName}"
    idx += 1
    ret
  }

  case class PlusArgBridgeInfo(
    newMod: Module, // new module def
    bridgeMod: ExtModule, // bridge module
    bridgeAnno: BridgeAnnotation) // bridge anno

  case class PlusArgInfo(
    default: Int,
    format: String, // name + '=%d'
    width: Int,
    docstring: String)

  def addBridgeInstanceInModule(
    parentMod: Module,
    plusArgInstanceName: String,
    plusArgWidth: Int,
    bridgeName: String
  ): Module= {
    val ref = plusArgInstanceName
    val newRef = ref + "_plusarg_wire"
    val argsW = IntWidth(BigInt(plusArgWidth))
    val parentModClockName = "clock"

    val body = parentMod.body
    val inst = DefInstance(bridgeName, bridgeName)
    val clkConn = Connect(NoInfo, WSubField(WRef(bridgeName), "clock"), WRef(parentModClockName))
    val refConn = Connect(NoInfo, WRef(newRef), WSubField(WRef(bridgeName), "io_out"))
    var replaced = false

    def swapRefs(e: Expression): Expression = {
      e match {
        // TODO: could match a bit stronger to the specific 'out' field
        case wsf @ WSubField(WRef(iN, _, InstanceKind, _), iP, _, _) =>
          if (iN == plusArgInstanceName) {
            replaced = true
            WRef(newRef)
          } else {
            wsf
          }
        case other =>
          other.mapExpr(swapRefs)
      }
    }

    val bStmts = Seq(inst, clkConn, refConn)
    val newBody = body match {
      case Block(stmts) =>
        val newStmts = stmts.map(s => s.mapExpr(swapRefs))

        val initialWireDef = if (replaced) {
          val wire = DefWire(NoInfo, newRef, firrtl.ir.UIntType(argsW))
          Seq(wire)
        } else {
          Seq.empty
        }

        new Block(initialWireDef ++ newStmts ++ bStmts)
      case _ => throw new Exception("Module body is not a Block")
    }
    parentMod.copy(body = newBody)
  }

  def onStmtRemoveDefInstance(stmt: Statement, instName: String): Seq[Statement] = {
    stmt match {
      case DefInstance(i, n, m, t) if (n == instName) => Seq.empty
      case s => Seq(s)
    }
  }

  def removeOrigInstanceInModule(
    parentMod: Module,
    instName: String
  ): Module= {
    val newBody = parentMod.body match {
      case Block(stmts) =>
        val newStmts = stmts.flatMap(onStmtRemoveDefInstance(_, instName))
        new Block(newStmts)
      case _ => throw new Exception("Module body is not a Block")
    }
    parentMod.copy(body = newBody)
  }

  def connectBridgeInsideModule(
    circuitMain: String,
    plusArgInfo: PlusArgInfo,
    plusArgInstanceName: String,
    parentMod: Module,
  ): PlusArgBridgeInfo = {
    val parentModNoExt = removeOrigInstanceInModule(parentMod, plusArgInstanceName)

    val argsW = IntWidth(BigInt(plusArgInfo.width))
    val bridgeName = getPlusArgBridgeName(plusArgInfo.format)
    val bClkPort  = Port(NoInfo, "clock",   Input,  ClockType)
    val bArgsPort = Port(NoInfo, "io_out", Output, firrtl.ir.UIntType(argsW))
    val bPorts = Seq(bClkPort, bArgsPort)
    val bModule = ExtModule(NoInfo, bridgeName, bPorts, bridgeName, Seq())

    val bmt = ModuleTarget(circuitMain, bridgeName)
    val bAnno = BridgeAnnotation(
      target = bmt,
      bridgeChannels = Seq(
        PipeBridgeChannel(
          name = "outChannel",
          clock = bmt.ref("clock"),
          sinks = Seq(bmt.ref("io_out")),
          sources = Seq(),
          latency = 0
        )),
      widgetClass = classOf[PlusArgsBridgeModule].getName,
      widgetConstructorKey = Some(PlusArgsBridgeParams(
        plusArgInfo.format,
        plusArgInfo.default,
        plusArgInfo.docstring,
        plusArgInfo.width)))
    val newMod = addBridgeInstanceInModule(parentModNoExt, plusArgInstanceName, plusArgInfo.width, bridgeName)
    PlusArgBridgeInfo(newMod, bModule, bAnno)
  }

  def connectBridgePerAnno(
    state: CircuitState,
    anno: PlusArgFirrtlAnnotation
  ): CircuitState = {
    val parentMods = state.circuit.modules.collect({ case m: Module if m.name == anno.target.module => m })
    assert(parentMods.size == 1)
    val parentMod = parentMods.head

    val extMods = state.circuit.modules.collect({ case m: ExtModule if m.name == anno.target.ofModule => m })
    assert(extMods.size == 1)
    val extMod = extMods.head

    val plusArgInfo = {
      val default = extMod.params.collectFirst({ case p: IntParam if p.name == "DEFAULT" => p.value }).get
      val format = extMod.params.collectFirst({ case p: StringParam if p.name == "FORMAT" => p.value.string }).get
      val width = extMod.params.collectFirst({ case p: IntParam if p.name == "WIDTH" => p.value }).get
      // TODO: no docstring given in ExtModule definition, ignore for now
      PlusArgInfo(default.toInt, format, width.toInt, "")
    }

    val info = connectBridgeInsideModule(state.circuit.main, plusArgInfo, anno.target.instance, parentMod)
    val newAnnos = state.annotations.toSeq :+ info.bridgeAnno
    val newMods = state.circuit.modules.flatMap {
      case m: Module if (m.name == anno.target.module) =>
        Seq(info.newMod, info.bridgeMod)
      case m: ExtModule if (m.name == anno.target.ofModule) =>
        Seq.empty
      case m => Seq(m)
    }
    val newCircuit = state.circuit.copy(modules=newMods)
    state.copy(circuit=newCircuit, annotations=newAnnos)
  }

  def connectBridge(
    state: CircuitState,
    annos: Seq[PlusArgFirrtlAnnotation]
  ): CircuitState = {
    annos.foldLeft(state)((s, a) => connectBridgePerAnno(s, a))
  }

  def doTransform(state: CircuitState): CircuitState = {
    val plusArgAnnos = state.annotations.collect {
      case a: PlusArgFirrtlAnnotation => a
    }

    if (!plusArgAnnos.isEmpty) {
      println(s"[PlusArgs] PlusArgs are:")
      plusArgAnnos.foreach(println(_))
      connectBridge(state, plusArgAnnos.toSeq)
    } else { state }
  }

  def execute(state: CircuitState): CircuitState = {
    val resolver = new ResolveAndCheck
    val updatedState = resolver.runTransform(doTransform(state))

    val emitter = new EmitFirrtl("post-plusargs-transform.fir")
    emitter.runTransform(updatedState)

    val annoEmitter = new fame.EmitFAMEAnnotations("post-plusargs-transform.json")
    annoEmitter.runTransform(updatedState)

    // Clean up annotations so that their ReferenceTargets, which
    // are implicitly marked as DontTouch, can be optimized across
    updatedState.copy(
      annotations = updatedState.annotations.filter {
        case PlusArgFirrtlAnnotation(_) => false
        case o => true
      })
  }
}
