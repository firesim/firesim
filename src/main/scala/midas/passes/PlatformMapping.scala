package midas
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import Utils._
import java.io.{File, FileWriter, StringWriter}

private[passes] class PlatformMapping(
    target: String,
    dir: File)
    (implicit param: config.Parameters) extends firrtl.passes.Pass {

  override def name = "[midas] Platform Mapping"

  private def dumpHeader(c: platform.PlatformShim) {
    def vMacro(arg: (String, Int)): String = s"`define ${arg._1} ${arg._2}\n"

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

  def run(c: Circuit) = {
    val sim = c match { case w: WCircuit => w.sim }
    lazy val shim = param(Platform) match {
      case Zynq     => new platform.ZynqShim(sim)
      case Catapult => new platform.CatapultShim(sim)
      case F1       => new platform.F1Shim(sim)
    }
    val chirrtl = Parser parse (chisel3.Driver emit (() => shim))
    val circuit = renameMods((new LowFirrtlCompiler compile (
      CircuitState(chirrtl, ChirrtlForm), new StringWriter)).circuit, Namespace(c))
    dumpHeader(shim)
    circuit.copy(modules = c.modules ++ (circuit.modules flatMap init(c.info, c.main)))
  }
}
