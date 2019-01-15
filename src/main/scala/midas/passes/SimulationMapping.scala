// See LICENSE for license details.

package midas
package passes

import java.io.{File, FileWriter, StringWriter}

import firrtl._
import firrtl.annotations.{CircuitName}
import firrtl.ir._
import firrtl.Mappers._
import firrtl.passes.LowerTypes.loweredName
import firrtl.Utils.{BoolType, splitRef, mergeRef, create_exps, gender, module_type}
import Utils._
import freechips.rocketchip.config.Parameters

import midas.core.SimWrapper

private[passes] class SimulationMapping(
    io: Seq[(String, chisel3.Data)])
  (implicit param: Parameters) extends firrtl.Transform {

  def inputForm = LowForm
  def outputForm = HighForm
  override def name = "[MIDAS] Simulation Mapping"

  private def initStmt(target: String)(s: Statement): Statement =
    s match {
      case s: WDefInstance if s.name == "target" && s.module == "TargetBox" => Block(Seq(
        s copy (module = target), // replace TargetBox with the actual target module
        IsInvalid(NoInfo, wref("target")) // FIXME: due to rocketchip
      ))
      case s => s map initStmt(target)
    }

  private def init(info: Info, target: String, main: String, tpe: Type)(m: DefModule) = m match {
    case m: Module if m.name == main =>
      val body = initStmt(target)(m.body)
      val stmts = Connect(NoInfo, wsub(wref("target"), "targetFire"), wref("fire", BoolType)) +:
      (if (!param(EnableSnapshot)) Nil
       else {
         val ports = (m.ports map (p => p.name -> p)).toMap
         (create_exps(wsub(wref("target", tpe), "daisy")) map { e =>
           val io = WRef(loweredName(mergeRef(wref("io"), splitRef(e)._2)))
           ports(io.name).direction match {
             case Input  => Connect(NoInfo, e, io)
             case Output => Connect(NoInfo, io, e)
           }
         }) ++ Seq(
           Connect(NoInfo, wsub(wref("target"), "daisyReset"), wref("reset", BoolType))
         )
       })
      Some(m copy (info = info, body = Block(body +: stmts)))
    case m: Module => Some(m)
    case m: ExtModule => None
  }

  def execute(innerState: CircuitState): CircuitState = {
    val innerCircuit = innerState.circuit

    // Look up new IO than may have been added by instrumentation or debugging passes
    val newTargetIO = innerState.annotations.collect({
      case a: AddedTargetIoAnnotation[_] => a.generateChiselIO()
    })
    val innerAnnos = innerState.annotations.flatMap({
      case _: AddedTargetIoAnnotation[_] => None
      case a => Some(a)
    })

    lazy val sim = new SimWrapper(io, newTargetIO)
    val c3circuit = chisel3.Driver.elaborate(() => sim)
    val chirrtl = Parser.parse(chisel3.Driver.emit(c3circuit))
    val annos = c3circuit.annotations.map(_.toFirrtl)

    val transforms = Seq(new PreLinkRenaming(Namespace(innerCircuit)))
    val outerState = new LowFirrtlCompiler().compile(CircuitState(chirrtl, ChirrtlForm, annos),
                                                     transforms)

    val outerCircuit = outerState.circuit
    val targetType = module_type((innerCircuit.modules find (_.name == innerCircuit.main)).get)
    val modules = innerCircuit.modules ++ (outerCircuit.modules flatMap
      init(innerCircuit.info, innerCircuit.main, outerCircuit.main, targetType))

    // Rename the annotations from the inner module, which are using an obsolete CircuitName
    val renameMap = RenameMap(
      Map(CircuitName(innerCircuit.main) -> Seq(CircuitName(outerCircuit.main))))

    CircuitState(
      circuit     = new WCircuit(outerCircuit.info, modules, outerCircuit.main, sim.io),
      form        = HighForm,
      annotations = innerAnnos ++ outerState.annotations,
      renames     = Some(renameMap)
    )
  }
}
