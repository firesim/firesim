// See LICENSE for license details.

package midas
package passes

import java.io.{File, FileWriter, StringWriter}

import scala.collection.mutable

import firrtl._
import firrtl.annotations.{CircuitName, ReferenceTarget, ModuleTarget}
import firrtl.ir._
import firrtl.Mappers._
import firrtl.passes.LowerTypes.loweredName
import firrtl.Utils.{BoolType, splitRef, mergeRef, create_exps, gender, module_type}
import firrtl.passes.wiring._
import fame.{FAMEChannelConnectionAnnotation, FAMEChannelAnalysis, FAME1Transform}
import Utils._
import freechips.rocketchip.config.Parameters

import midas.core._
import midas.widgets.BridgeIOAnnotation

private[passes] object ReferenceTargetToPortMap {
  def apply(state: CircuitState): Map[ReferenceTarget, Port] = {
    val circuit = state.circuit
    val portNodes = new mutable.LinkedHashMap[ReferenceTarget, Port]
    val moduleNodes = new mutable.LinkedHashMap[ModuleTarget, DefModule]
    circuit.modules.filter(_.name == circuit.main).foreach({m =>
      val mTarget = ModuleTarget(circuit.main, m.name)
      m.ports.foreach({ p => portNodes(mTarget.ref(p.name)) = p })
    })
    portNodes.toMap
  }
}

private[passes] class SimulationMapping(targetName: String)(implicit val p: Parameters) extends firrtl.Transform
    with HasSimWrapperParams {

  def inputForm = LowForm
  def outputForm = HighForm
  override def name = "[Golden Gate] Simulation Mapping"

  private def dumpHeader(c: platform.PlatformShim) {
    def vMacro(arg: (String, Long)): String = s"`define ${arg._1} ${arg._2}\n"

    val dir = p(OutputDir)
    val csb = new StringBuilder
    csb append "#ifndef __%s_H\n".format(targetName.toUpperCase)
    csb append "#define __%s_H\n".format(targetName.toUpperCase)
    c.genHeader(csb, targetName)
    csb append "#endif  // __%s_H\n".format(targetName.toUpperCase)

    val vsb = new StringBuilder
    vsb append "`ifndef __%s_H\n".format(targetName.toUpperCase)
    vsb append "`define __%s_H\n".format(targetName.toUpperCase)
    c.headerConsts map vMacro addString vsb
    vsb append "`endif  // __%s_H\n".format(targetName.toUpperCase)

    val ch = new FileWriter(new File(dir, s"${targetName}-const.h"))
    val vh = new FileWriter(new File(dir, s"${targetName}-const.vh"))

    try {
      ch write csb.result
      vh write vsb.result
    } finally {
      ch.close
      vh.close
      csb.clear
      vsb.clear
    }
  }

  // Note: this only runs on the SimulationWrapper Module
  private def initStmt(targetModuleName: String, targetInstName: String)(s: Statement): Statement =
    s match {
      case s @ WDefInstance(_, name, _, _) if name == targetInstName =>
        Block(Seq(
          s copy (module = targetModuleName), // replace TargetBox with the actual target module
          IsInvalid(NoInfo, wref(name))
        ))
      case s => s map initStmt(targetModuleName, targetInstName)
    }

  private def init(info: Info, target: String, tpe: Type, targetBoxRT: ReferenceTarget)(m: DefModule) = m match {
    case m: Module if m.name == targetBoxRT.module =>
      val targetBoxInstName = targetBoxRT.ref
      val body = initStmt(target, targetBoxInstName)(m.body)
      val stmts = if (!p(EnableSnapshot)) {
        Seq()
      } else {
         val ports = (m.ports map (p => p.name -> p)).toMap
         (create_exps(wsub(wref(targetBoxInstName, tpe), "daisy")) map { e =>
           val io = WRef(loweredName(mergeRef(wref("io"), splitRef(e)._2)))
           ports(io.name).direction match {
             case Input  => Connect(NoInfo, e, io)
             case Output => Connect(NoInfo, io, e)
           }
         }) ++ Seq(
           Connect(NoInfo, wsub(wref(targetBoxInstName), "daisyReset"), wref("reset", BoolType))
         )
       }
      Some(m copy (info = info, body = Block(body +: stmts)))
    case m: Module => Some(m)
    case m: ExtModule => None
  }

  def execute(innerState: CircuitState) = {

    // Grab the FAME-transformed circuit; collect all fame channel annotations and pass them to 
    // SimWrapper generation. We want the targets to point at un-lowered ports
    val chAnnos = innerState.annotations.collect({ case ch: FAMEChannelConnectionAnnotation => ch })
    val bridgeAnnos = innerState.annotations.collect({ case ep: BridgeIOAnnotation => ep })
    // Generate a port map to look up the types of the IO of the channels
    val portTypeMap: Map[ReferenceTarget, Port] = ReferenceTargetToPortMap(innerState)
    val simWrapperConfig = SimWrapperConfig(chAnnos, bridgeAnnos, portTypeMap)

    // Now lower the inner circuit in preparation for linking
    // This prevents having to worry about matching aggregate structure in the wrapper IO
    val loweredInnerState = new IntermediateLoweringCompiler(innerState.form, LowForm).compile(innerState, Seq())
    val innerCircuit = loweredInnerState.circuit

    val completeParams = p.alterPartial({ case SimWrapperKey => simWrapperConfig })
    lazy val shim = p(Platform)(completeParams)
    val c3circuit = chisel3.Driver.elaborate(() => shim)
    val chirrtl = Parser.parse(chisel3.Driver.emit(c3circuit))
    val annos = c3circuit.annotations.map(_.toFirrtl)

    val transforms = Seq(
      new Fame1Instances,
      new WiringTransform,
      new PreLinkRenaming(Namespace(innerCircuit)))
    val outerState = new LowFirrtlCompiler().compile(CircuitState(chirrtl, ChirrtlForm, annos), transforms)

    val outerCircuit = outerState.circuit
    val targetType = module_type((innerCircuit.modules find (_.name == innerCircuit.main)).get)
    val targetBoxAnno = outerState.annotations.collectFirst({ case c: TargetBoxAnnotation => c }).get
    val modules = innerCircuit.modules ++ (outerCircuit.modules flatMap
      init(innerCircuit.info, innerCircuit.main, targetType, targetBoxAnno.target))

    // Rename the annotations from the inner module, which are using an obsolete CircuitName
    val renameMap = RenameMap(
      Map(CircuitName(innerCircuit.main) -> Seq(CircuitName(outerCircuit.main))))

    // FIXME: Renamer complains if i leave these in
    val innerAnnos = loweredInnerState.annotations.filter(_ match {
      case _: FAMEChannelConnectionAnnotation => false
      case _: BridgeIOAnnotation => false
      case _ => true
    })

    val linkedState = CircuitState(
      circuit     = Circuit(outerCircuit.info, modules, outerCircuit.main),
      form        = HighForm,
      annotations = innerAnnos ++ outerState.annotations,
      renames     = Some(renameMap)
    )
    writeState(linkedState, "post-sim-mapping.fir")
    dumpHeader(shim)
    linkedState
  }
}

