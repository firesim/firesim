// See LICENSE for license details.

package midas
package passes

import java.io.{File, FileWriter, StringWriter}

import scala.collection.mutable

import firrtl._
import firrtl.annotations.{CircuitName, ReferenceTarget, ModuleTarget, InstanceTarget}
import firrtl.options.Dependency
import firrtl.stage.Forms
import firrtl.stage.transforms.Compiler
import firrtl.ir._
import firrtl.Mappers._
import firrtl.passes.LowerTypes.loweredName
import firrtl.Utils.{BoolType, splitRef, mergeRef, create_exps, flow, module_type}
import firrtl.passes.wiring._
import Utils._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy.LazyModule

import midas.core._
import midas.platform.PlatformShim

private[passes] class SimulationMapping(targetName: String) extends firrtl.Transform {
  def inputForm = LowForm
  def outputForm = HighForm
  override def name = "[Golden Gate] Simulation Mapping"

  private def dumpHeader(c: PlatformShim,  dir: File) {
    def vMacro(arg: (String, Long)): String = s"`define ${arg._1} ${arg._2}\n"

    val csb = new StringBuilder
    csb append "#ifndef __%s_H\n".format(targetName.toUpperCase)
    csb append "#define __%s_H\n".format(targetName.toUpperCase)
    c.genHeader(csb, targetName)
    csb append "#endif  // __%s_H\n".format(targetName.toUpperCase)

    val vsb = new StringBuilder
    vsb append "`ifndef __%s_H\n".format(targetName.toUpperCase)
    vsb append "`define __%s_H\n".format(targetName.toUpperCase)
    c.top.module.headerConsts map vMacro addString vsb
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

  private def init(info: Info, target: String, tpe: Type, targetBoxParent: String, targetBoxInst: String)(m: DefModule) = m match {
    case m: Module if m.name == targetBoxParent =>
      val body = initStmt(target, targetBoxInst)(m.body)
      Some(m.copy(info = info, body = body))
    case m: Module => Some(m)
    case m: ExtModule => None
  }

  def execute(innerState: CircuitState) = {
    // Generate a port map to look up the types of the IO of the channels
    implicit val p = innerState.annotations.collectFirst({ case midas.stage.phases.ConfigParametersAnnotation(p)  => p }).get
    val circuit = innerState.circuit
    val portTypeMap = circuit.modules.filter(_.name == circuit.main).flatMap({m =>
      val mTarget = ModuleTarget(circuit.main, m.name)
      m.ports.map({ p => mTarget.ref(p.name) ->  p })
    }).toMap

    // Lower the inner circuit in preparation for linking
    // This prevents having to worry about matching aggregate structure in the wrapper IO
    val loweredInnerState = new Compiler(Forms.LowForm, Forms.HighForm).execute(innerState)
    val innerCircuit = loweredInnerState.circuit

    // Generate the encapsulating simulator RTL
    lazy val shim = PlatformShim(innerState.annotations, portTypeMap)
    val c3circuit = chisel3.stage.ChiselStage.elaborate(LazyModule(shim).module)
    val chirrtl = chisel3.stage.ChiselStage.convert(c3circuit)
    val annos = PreLinkRenamingAnnotation(Namespace(innerCircuit)) +: c3circuit.annotations.map(_.toFirrtl)

    val transforms = Seq(
      Dependency[midas.passes.DedupModules],
      Dependency[Fame1Instances],
      Dependency(PreLinkRenaming))
    val outerState = new Compiler(Forms.LowForm ++ transforms)
      .execute(CircuitState(chirrtl, ChirrtlForm, annos))

    val outerCircuit = outerState.circuit
    val targetType = module_type((innerCircuit.modules find (_.name == innerCircuit.main)).get)
    val targetBoxInstTarget = outerState.annotations.collectFirst({
      case TargetBoxAnnotation(it: InstanceTarget) => it
    }).getOrElse(throw new Exception("TargetBoxAnnotation not found or annotated top module!"))
    val targetBoxParent = targetBoxInstTarget.encapsulatingModule
    val targetBoxInst = targetBoxInstTarget.instance
    val modules = innerCircuit.modules ++ (outerCircuit.modules flatMap
      init(innerCircuit.info, innerCircuit.main, targetType, targetBoxParent, targetBoxInst))

    // Rename the annotations from the inner module, which are using an obsolete CircuitName
    val renameMap = RenameMap(
      Map(CircuitName(innerCircuit.main) -> Seq(CircuitName(outerCircuit.main))))

    val innerAnnos = loweredInnerState.annotations.filter(_ match {
      case _: midas.targetutils.FAMEAnnotation => false
      case _ => true
    })

    val linkedState = CircuitState(
      circuit     = Circuit(outerCircuit.info, modules, outerCircuit.main),
      form        = HighForm,
      annotations = innerAnnos ++ outerState.annotations,
      renames     = Some(renameMap)
    )
    writeState(linkedState, "post-sim-mapping.fir")
    dumpHeader(shim, p(OutputDir))
    linkedState
  }
}

