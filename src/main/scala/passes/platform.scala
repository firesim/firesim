package strober
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import chisel3.Bits
import Utils._
import StroberTransforms._
import java.io.{File, FileWriter, Writer, StringWriter}

private[passes] class PlatformMapping(
    target: String,
    dir: File,
    chainFile: FileWriter)
    (implicit param: cde.Parameters) extends firrtl.passes.Pass {

  def name = "[strober] Platform Mapping"

  private class PlatformCompiler extends firrtl.Compiler {
    def transforms(writer: Writer) = Seq(
      new Chisel3ToHighFirrtl,
      new IRToWorkingIR,
      new ResolveAndCheck,
      new HighFirrtlToMiddleFirrtl,
      new EmitFirrtl(writer) // debugging
    )
  }

  private object TraceType extends Enumeration {
    val InTr = Value(ChainType.values.size)
    val OutTr = Value(ChainType.values.size + 1)
  }

  private def dumpTraceMap(c: ZynqShim) {
    implicit val channelWidth = c.sim.channelWidth
    val ioMap = (c.simIo.inputs ++ c.simIo.outputs).toMap
    val sb = new StringBuilder
    def dump(t: TraceType.Value)(arg: (Bits, Int)) = arg match {
      case (wire, id) => s"${t.id} ${ioMap(wire)} ${id} ${SimUtils.getChunks(wire)}\n"
    }
    c.IN_TR_ADDRS map dump(TraceType.InTr) addString sb
    c.OUT_TR_ADDRS map dump(TraceType.OutTr) addString sb
    try {
      chainFile write sb.result
    } finally {
      chainFile.close
      sb.clear
    }
  }

  private def dumpHeader(c: ZynqShim) {
    implicit val channelWidth = c.sim.channelWidth
    val ioMap = (c.simIo.inputs ++ c.simIo.outputs).toMap
    def dump(arg: (String, Int)): String =
      s"#define ${arg._1} ${arg._2}\n"
    def dumpId(arg: (Bits, Int)): String =
      s"#define ${ioMap(arg._1)} ${arg._2}\n"
    def dumpNames(arg: (Bits, Int)): Seq[String] = {
      val chunks = SimUtils.getChunks(arg._1)
      (0 until chunks) map (i => if (i == 0) "  \"%s\"".format(ioMap(arg._1)) else "  \"\"")
    }
    def dumpChunks(arg: (Bits, Int)): Seq[Int] = {
      val chunks = SimUtils.getChunks(arg._1)
      (0 until chunks) map (i => if (i == 0) chunks else 0)
    }
    def vdump(arg: (String, Int)): String =
      s"`define ${arg._1} ${arg._2}\n"

    val consts = List(
      "CHANNEL_ID_BITS"   -> c.master.nastiExternal.idBits,
      "CHANNEL_ADDR_BITS" -> c.master.nastiXAddrBits,
      "CHANNEL_DATA_BITS" -> c.master.nastiXDataBits,
      "CHANNEL_STRB_BITS" -> c.master.nastiWStrobeBits,
      "MEM_ID_BITS"       -> c.arb.nastiExternal.idBits,
      "MEM_ADDR_BITS"     -> c.arb.nastiXAddrBits,
      "MEM_DATA_BITS"     -> c.arb.nastiXDataBits,
      "MEM_STRB_BITS"     -> c.arb.nastiWStrobeBits,

      "POKE_SIZE"         -> c.ins.size,
      "PEEK_SIZE"         -> c.outs.size,
      "MEM_DATA_CHUNK"    -> SimUtils.getChunks(c.io.slave.w.bits.data),
      "MEM_AR_ADDR"       -> c.AR_ADDR,
      "MEM_AW_ADDR"       -> c.AW_ADDR,
      "MEM_W_ADDR"        -> c.W_ADDR,
      "MEM_R_ADDR"        -> c.R_ADDR
    ) ++ c.sim.headerConsts
    val csb = new StringBuilder
    csb append "#ifndef __%s_H\n".format(target.toUpperCase)
    csb append "#define __%s_H\n".format(target.toUpperCase)
    csb append "static const char* const TARGET_NAME = \"%s\";\n".format(target)
    if (c.sim.enableSnapshot) csb append "#define ENABLE_SNAPSHOT\n"
    consts map dump addString csb
    csb append "// IDs assigned to I/Os\n"
    c.IN_ADDRS map dumpId addString csb
    c.OUT_ADDRS map dumpId addString csb
    c.genHeader(csb)
    csb append "static const char* const INPUT_NAMES[POKE_SIZE] = {\n%s\n};\n".format(
      c.IN_ADDRS flatMap dumpNames mkString ",\n")
    csb append "static const char* const OUTPUT_NAMES[PEEK_SIZE] = {\n%s\n};\n".format(
      c.OUT_ADDRS flatMap dumpNames mkString ",\n")
    csb append "static const unsigned INPUT_CHUNKS[POKE_SIZE] = {%s};\n".format(
      c.IN_ADDRS flatMap dumpChunks mkString ",")
    csb append "static const unsigned OUTPUT_CHUNKS[PEEK_SIZE] = {%s};\n".format(
      c.OUT_ADDRS flatMap dumpChunks mkString ",")
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
    val annotations = new Annotations.AnnotationMap(Nil)
    val writer = new StringWriter
    // val writer = new FileWriter(new File("ZynqShim.ir"))
    val circuit = renameMods((new PlatformCompiler compile
      (chirrtl, annotations, writer)).circuit, Namespace(c))
    // writer.close
    dumpTraceMap(shim)
    dumpHeader(shim)
    circuit copy (modules = c.modules ++ (
      circuit.modules flatMap init(c.info, c.main, circuit.main)))
  }
}
