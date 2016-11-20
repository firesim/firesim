package midas
package passes

import midas.core.ZynqShim
import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import chisel3.Bits
import Utils._
import MidasTransforms._
import java.io.{File, FileWriter, Writer, StringWriter}

private[passes] class PlatformMapping(
    target: String,
    dir: File)
    (implicit param: cde.Parameters) extends firrtl.passes.Pass {

  def name = "[midas] Platform Mapping"

  private def dumpHeader(c: ZynqShim) {
    implicit val channelWidth = c.sim.channelWidth
    val ioMap = (c.simIo.inputs ++ c.simIo.outputs).toMap
    def dump(arg: (String, Int)): String = s"#define ${arg._1} ${arg._2}\n"
    def vdump(arg: (String, Int)): String = s"`define ${arg._1} ${arg._2}\n"

    val consts = List(
      "CHANNEL_ID_BITS"   -> c.master.io.ctrl.nastiExternal.idBits,
      "CHANNEL_ADDR_BITS" -> c.master.io.ctrl.nastiXAddrBits,
      "CHANNEL_DATA_BITS" -> c.master.io.ctrl.nastiXDataBits,
      "CHANNEL_STRB_BITS" -> c.master.io.ctrl.nastiWStrobeBits,
      "MEM_ID_BITS"       -> c.arb.nastiExternal.idBits,
      "MEM_ADDR_BITS"     -> c.arb.nastiXAddrBits,
      "MEM_DATA_BITS"     -> c.arb.nastiXDataBits,
      "MEM_STRB_BITS"     -> c.arb.nastiWStrobeBits
    ) ++ c.sim.headerConsts
    val csb = new StringBuilder
    csb append "#ifndef __%s_H\n".format(target.toUpperCase)
    csb append "#define __%s_H\n".format(target.toUpperCase)
    csb append "static const char* const TARGET_NAME = \"%s\";\n".format(target)
    if (c.sim.enableSnapshot) csb append "#define ENABLE_SNAPSHOT\n"
    consts map dump addString csb
    c.genHeader(csb)
    csb append "#endif  // __%s_H\n".format(target.toUpperCase)

    val vsb = new StringBuilder
    vsb append "`ifndef __%s_H\n".format(target.toUpperCase)
    vsb append "`define __%s_H\n".format(target.toUpperCase)
    consts map vdump addString vsb
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
        s copy (module = sim) // replace TargetBox with the actual sim module
      case s => s map initStmt(sim)
    }

  def init(info: Info, sim: String, main: String)(m: DefModule) = m match {
    case m: Module if m.name == main =>
      val body = initStmt(sim)(m.body)
      Some(m copy (info = info, body = body))
    case m: Module => Some(m)
    case m: ExtModule => None
  }

  def run(c: Circuit) = {
    val (sim, mem) = c match { case w: WCircuit => (w.sim, w.mem) }
    lazy val shim = new ZynqShim(sim, mem)
    val chirrtl = Parser parse (chisel3.Driver emit (() => shim))
    val writer = new StringWriter
    // val writer = new FileWriter(new File("ZynqShim.ir"))
    val circuit = renameMods((new InlineCompiler compile (
      CircuitState(chirrtl, ChirrtlForm), writer)).circuit, Namespace(c))
    // writer.close
    dumpHeader(shim)
    circuit copy (modules = c.modules ++ (
      circuit.modules flatMap init(c.info, c.main, circuit.main)))
  }
}
