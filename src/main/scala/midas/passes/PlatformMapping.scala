// See LICENSE for license details.

package midas
package passes

import firrtl._
import firrtl.annotations.{CircuitName}
import firrtl.ir._
import firrtl.Mappers._
import Utils._
import java.io.{File, FileWriter, StringWriter}

private[passes] class PlatformMapping(
    target: String,
    dir: File)
  (implicit param: freechips.rocketchip.config.Parameters) extends firrtl.Transform {

  def inputForm = LowForm
  def outputForm = HighForm
  override def name = "[MIDAS] Platform Mapping"

  private def dumpHeader(c: platform.PlatformShim) {
    def vMacro(arg: (String, Long)): String = s"`define ${arg._1} ${arg._2}\n"

    val csb = new StringBuilder
    csb append "#ifndef __%s_H\n".format(target.toUpperCase)
    csb append "#define __%s_H\n".format(target.toUpperCase)
    c.genHeader(csb, target)
    csb append "#endif  // __%s_H\n".format(target.toUpperCase)

    val vsb = new StringBuilder
    vsb append "`ifndef __%s_H\n".format(target.toUpperCase)
    vsb append "`define __%s_H\n".format(target.toUpperCase)
    c.headerConsts map vMacro addString vsb
    vsb append "`endif  // __%s_H\n".format(target.toUpperCase)

    val ch = new FileWriter(new File(dir, s"${target}-const.h"))
    val vh = new FileWriter(new File(dir, s"${target}-const.vh"))

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

  def initStmt(sim: String)(s: Statement): Statement =
    s match {
      case s: WDefInstance if s.name == "sim" && s.module == "SimBox" =>
        s.copy(module = sim) // replace TargetBox with the actual sim module
      case s => s map initStmt(sim)
    }

  def init(info: Info, sim: String)(m: DefModule) = m match {
    case m: Module if m.name == "FPGATop" =>
      val body = initStmt(sim)(m.body)
      Some(m.copy(info = info, body = body))
    case m: Module => Some(m)
    case m: ExtModule => None
  }

  def linkCircuits(parent: Circuit, child: Circuit): Circuit = {
    parent.copy(modules = child.modules ++ (parent.modules flatMap init(child.info, child.main)))
  }

  def execute(c: CircuitState) = {
    val sim = c.circuit match { case w: WCircuit => w.sim }
    lazy val shim = param(Platform) match {
      case Zynq     => new platform.ZynqShim(sim)
      case F1       => new platform.F1Shim(sim)
    }
    val shimCircuit = chisel3.Driver.elaborate(() => shim)
    val chirrtl = Parser.parse(chisel3.Driver.emit(shimCircuit))
    val shimAnnos = shimCircuit.annotations.map(_.toFirrtl)
    val transforms = Seq(new Fame1Instances,
                         new PreLinkRenaming(Namespace(c.circuit)))
    val shimCircuitState = new LowFirrtlCompiler().compile(CircuitState(chirrtl, ChirrtlForm, shimAnnos), transforms)

    // Rename the annotations from the inner module, which are using an obselete CircuitName
    val renameMap = RenameMap(
      Map(CircuitName(c.circuit.main) -> Seq(CircuitName(shimCircuitState.circuit.main))))

    dumpHeader(shim)
    c.copy(circuit = linkCircuits(shimCircuitState.circuit, c.circuit),
           annotations = shimCircuitState.annotations ++ c.annotations,
           renames = Some(renameMap))
  }
}
