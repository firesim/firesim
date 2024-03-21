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
class PlusArgsWiringTransform extends Transform {
  def inputForm: CircuitForm = LowForm
  def outputForm: CircuitForm = MidForm
  override def name = "[Golden Gate] PlusArgs Transform"

  private var idx = 0
  def getPlusArgBridgeName(argName: String): String = {
    val ret = s"PlusArgsBridge_${argName}"
    idx += 1
    ret
  }

  case class plusArgsBridgeInfo(
    newMod: Module, // New module def
    bridgeMod: ExtModule, // Bridge module
    bridgeAnno: BridgeAnnotation) // bridge anno

  def onStmt(stmt: Statement, ref: String, newRef: String, argsW: IntWidth): Seq[Statement] = {
    stmt match {
      case DefNode(i, n, v) if (n == ref) =>
        val wire = DefWire(NoInfo, newRef, firrtl.ir.UIntType(argsW))
        val newDef = DefNode(i, n, WRef(newRef))
        Seq(wire, newDef)
      case s => Seq(s)
    }
  }

  def addBridgeInstanceInModule(
    mod: Module,
    anno: PlusArgsFirrtlAnnotation,
    bridgeName: String
  ): Module= {
    val ref = anno.target.name
    val newRef = ref + "_plusargs_"
    val argsW = IntWidth(BigInt(anno.width))

    val body = mod.body
    val inst = DefInstance(bridgeName, bridgeName)
    val clkConn = Connect(NoInfo, WSubField(WRef(bridgeName), "clock"), WRef(anno.clock.name))
    val refConn = Connect(NoInfo, WRef(newRef), WSubField(WRef(bridgeName), "io_out"))

    val bStmts = Seq(inst, clkConn, refConn)
    val newBody = body match {
      case Block(stmts) =>
        val newStmts = stmts.flatMap(onStmt(_, ref, newRef, argsW))
        new Block(newStmts ++ bStmts)
      case _ => throw new Exception("Module body is not a Block")
    }
    mod.copy(body = newBody)
  }

  def connectBridgeInsideModule(
    circuitMain: String,
    mod: Module,
    anno: PlusArgsFirrtlAnnotation
  ): plusArgsBridgeInfo = {
    val argsW = IntWidth(BigInt(anno.width))
    val name = getPlusArgBridgeName(anno.target.name)
    val bClkPort  = Port(NoInfo, "clock",   Input,  ClockType)
    val bArgsPort = Port(NoInfo, "io_out", Output, firrtl.ir.UIntType(argsW))
    val bPorts = Seq(bClkPort, bArgsPort)
    val bModule = ExtModule(NoInfo, name, bPorts, name, Seq())

    val bmt = ModuleTarget(circuitMain, name)
    val bAnno = BridgeAnnotation(
      target = bmt,
      bridgeChannels = Seq(
        PipeBridgeChannel(
          name = "outChannel",
          clock = bmt.ref("clock"),
          sinks = Seq(bmt.ref("io_out")),
          sources = Seq(),
          latency = 0
        )
    ),
      widgetClass = classOf[PlusArgsBridgeModule].getName,
      widgetConstructorKey = Some(PlusArgsBridgeParams(
        anno.name,
        anno.default,
        anno.docstring,
        anno.width)))
    val newMod = addBridgeInstanceInModule(mod, anno, name)
    plusArgsBridgeInfo(newMod, bModule, bAnno)
  }

  def connectBridgePerAnno(
    state: CircuitState,
    anno: PlusArgsFirrtlAnnotation
  ): CircuitState = {
    val iKeyGraph = InstanceKeyGraph(state.circuit)
    val iKeyPath = iKeyGraph.findInstancesInHierarchy(anno.target.module)

    // Limit the number of instances with modules containing PlusArgs annotations to one.
    // This isn't a big problem since we use the no-dedup flag for FireSim flows so
    // big enough modules have separate names anyways.
    assert(iKeyPath.size == 1, "Should only contain one instance of this module")
    val ik = iKeyPath(0).last
    val moduleDefs = state.circuit.modules.collect({
      case m: Module => OfModule(m.name) -> m
    }).toMap
    val mdef = moduleDefs(ik.OfModule)
    print(iKeyPath(0).last)

    val info = connectBridgeInsideModule(state.circuit.main, mdef, anno)
    val newAnnos = state.annotations.toSeq :+ info.bridgeAnno
    val newMods = state.circuit.modules.flatMap {
      case m: Module if (m.name == ik.module) =>
        Seq(info.newMod, info.bridgeMod)
      case m => Seq(m)
    }
    val newCircuit = state.circuit.copy(modules=newMods)
    state.copy(circuit=newCircuit, annotations=newAnnos)
  }

  def connectBridge(
    state: CircuitState,
    annos: Seq[PlusArgsFirrtlAnnotation]
  ): CircuitState = {
    annos.foldLeft(state)((s, a) => connectBridgePerAnno(s, a))
  }

  def doTransform(state: CircuitState): CircuitState = {
    val plusArgsAnnos = state.annotations.collect {
      case a: PlusArgsFirrtlAnnotation => a
    }

    if (!plusArgsAnnos.isEmpty) {
      println(s"[PlusArgs] PlusArgs are:")
      plusArgsAnnos.foreach({ case anno => println(s"   Name: ${anno.name} Docstring: ${anno.docstring} DefaultValue: ${anno.default} Width: ${anno.width}") })
      connectBridge(state, plusArgsAnnos.toSeq)
    } else { state }
  }

  def execute(state: CircuitState): CircuitState = {
    val resolver = new ResolveAndCheck
    val updatedState = resolver.runTransform(doTransform(state))

    val emitter = new EmitFirrtl("post-plusargs-transform.fir")
    emitter.runTransform(updatedState)

    // Clean up annotations so that their ReferenceTargets, which
    // are implicitly marked as DontTouch, can be optimized across
    updatedState.copy(
      annotations = updatedState.annotations.filter {
        case PlusArgsFirrtlAnnotation(_,_,_,_,_,_,_) => false
        case o => true
      })
  }
}
