package midas
package passes

import midas.core.SimWrapper
import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.Utils.BoolType
import Utils._
import MidasTransforms._
import java.io.{File, FileWriter, StringWriter}

private[passes] class SimulationMapping(
    io: chisel3.Data)
   (implicit param: config.Parameters) extends firrtl.passes.Pass {
  
  override def name = "[midas] Simulation Mapping"

  private def initStmt(target: String)(s: Statement): Statement =
    s match {
      case s: WDefInstance if s.name == "target" && s.module == "TargetBox" =>
        s copy (module = target) // replace TargetBox with the actual target module
      case s => s map initStmt(target)
    }

  private def init(info: Info, target: String, main: String)(m: DefModule) = m match {
    case m: Module if m.name == main =>
      val body = initStmt(target)(m.body)
      val stmts = Seq(
        Connect(NoInfo, wsub(wref("target"), "targetFire"), wref("fire", BoolType)),
        Connect(NoInfo, wsub(wref("target"), "daisyReset"), wref("reset", BoolType))) ++
      (if (!param(EnableSnapshot)) Nil else Seq(
        Connect(NoInfo, wsub(wref("io"), "daisy"), wsub(wref("target"), "daisy"))))
      Some(m copy (info = info, body = Block(body +: stmts)))
    case m: Module => Some(m)
    case m: ExtModule => None
  }

  def run(c: Circuit) = {
    lazy val sim = new SimWrapper(io)
    val chirrtl = Parser parse (chisel3.Driver emit (() => sim))
    val annotations = new AnnotationMap(Nil)
    val writer = new StringWriter
    // val writer = new FileWriter(new File("SimWrapper.ir"))
    val circuit = renameMods((new InlineCompiler compile (
      CircuitState(chirrtl, ChirrtlForm), writer)).circuit, Namespace(c))
    val modules = c.modules ++ (circuit.modules flatMap init(c.info, c.main, circuit.main))
    // writer.close
    new WCircuit(circuit.info, modules, circuit.main, sim.io)
  }
}
