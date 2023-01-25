// See LICENSE for license details.

package midas
package passes



import firrtl._
import firrtl.annotations.{CircuitName, ModuleTarget, InstanceTarget}
import firrtl.options.Dependency
import firrtl.stage.Forms
import firrtl.stage.transforms.Compiler
import firrtl.ir._
import firrtl.Mappers._
import firrtl.Utils.module_type
import midas.passes.Utils._
import freechips.rocketchip.diplomacy.LazyModule

import midas.core._
import midas.platform.PlatformShim
import midas.stage.{OutputFileBuilder, GoldenGateOutputFileAnnotation}

private[passes] class SimulationMapping(targetName: String) extends firrtl.Transform {
  def inputForm = LowForm
  def outputForm = HighForm
  override def name = "[Golden Gate] Simulation Mapping"

  private def generateHeaderAnnos(c: PlatformShim): Seq[GoldenGateOutputFileAnnotation] = {
    val csb = new OutputFileBuilder(
      """// Golden Gate-generated Driver Header
        |// This contains target-specific preprocessor macro definitions,
        |// and encodes all required bridge metadata to instantiate bridge drivers.
        |""".stripMargin,
      fileSuffix = ".const.h")
    csb append "#ifndef __%s_H\n".format(targetName.toUpperCase)
    csb append "#define __%s_H\n".format(targetName.toUpperCase)
    c.genHeader(csb.getBuilder, targetName)
    csb append "#endif  // __%s_H\n".format(targetName.toUpperCase)

    val vsb = new OutputFileBuilder(
      """// Golden Gate-generated Verilog Header
        |// This file encodes variable width fields used in MIDAS-level simulation
        |// and is not used in FPGA compilation flows.
        |""".stripMargin,
      fileSuffix = ".const.vh")

    vsb append "`ifndef __%s_H\n".format(targetName.toUpperCase)
    vsb append "`define __%s_H\n".format(targetName.toUpperCase)
    c.genVHeader(vsb.getBuilder, targetName)
    vsb append "`endif  // __%s_H\n".format(targetName.toUpperCase)
    Seq(csb.toAnnotation, vsb.toAnnotation)
  }

  // Note: this only runs on the SimulationWrapper Module
  private def initStmt(targetModuleName: String, targetInstName: String)(s: Statement): Statement =
    s match {
      case s @ WDefInstance(_, name, _, _) if name == targetInstName =>
        Block(Seq(
          s copy (module = targetModuleName), // replace TargetBox with the actual target module
          IsInvalid(NoInfo, WRef(name))
        ))
      case s => s map initStmt(targetModuleName, targetInstName)
    }

  private def init(info: Info, target: String, tpe: Type, targetBoxParent: String, targetBoxInst: String)(m: DefModule) = m match {
    case m: Module if m.name == targetBoxParent =>
      val body = initStmt(target, targetBoxInst)(m.body)
      Some(m.copy(info = info, body = body))
    case o => Some(o)
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
    lazy val shim = PlatformShim(SimWrapperConfig(innerState.annotations, portTypeMap))
    val (chirrtl, elaboratedAnnos) = midas.targetutils.ElaborateChiselSubCircuit(LazyModule(shim).module)

    val outerAnnos = PreLinkRenamingAnnotation(Namespace(innerCircuit)) +: elaboratedAnnos
    val outerState = new Compiler(Forms.LowForm ++ Seq(Dependency(PreLinkRenaming)))
      .execute(CircuitState(chirrtl, ChirrtlForm, outerAnnos))

    val outerCircuit = outerState.circuit
    val targetType = module_type((innerCircuit.modules find (_.name == innerCircuit.main)).get)
    val targetBoxInstTarget = outerState.annotations.collectFirst({
      case TargetBoxAnnotation(it: InstanceTarget) => it
    }).getOrElse(throw new Exception("TargetBoxAnnotation not found or annotated top module!"))
    val targetBoxParent = targetBoxInstTarget.encapsulatingModule
    val targetBoxInst = targetBoxInstTarget.instance
    val modules = (outerCircuit.modules flatMap
      init(innerCircuit.info, innerCircuit.main, targetType, targetBoxParent, targetBoxInst)) ++
      innerCircuit.modules
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
    linkedState.copy(annotations = linkedState.annotations ++ generateHeaderAnnos(shim))
  }
}

