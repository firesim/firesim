package strober
package passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.passes.bitWidth
import firrtl.Utils.{BoolType, create_exps}
import firrtl.passes.LowerTypes.loweredName
import firrtl.passes.VerilogRename.verilogRenameN
import chisel3.{Data, Bits}
import java.io.{File, FileWriter, Writer, StringWriter}
import Utils._
import StroberTransforms._

private[passes] class PlatformMapping(
    dir: File,
    io: Data,
    childInsts: ChildInsts,
    instModMap: InstModMap,
    chains: Map[ChainType.Value, ChainMap],
    seqMems: Map[String, MemConf])
    (implicit param: cde.Parameters) extends firrtl.passes.Pass {

  def name = "[strober] Platform Mapping"

  private class WrappingCompiler extends firrtl.Compiler {
    def transforms(writer: Writer) = Seq(
      new Chisel3ToHighFirrtl,
      new IRToWorkingIR,
      new ResolveAndCheck,
      new HighFirrtlToMiddleFirrtl,
      new EmitFirrtl(writer) // debugging
    )
  }

  private def addPad(w: Writer, cw: Int, dw: Int)(chainType: ChainType.Value) {
    (cw - dw) match {
      case 0 =>
      case pad => w write s"${chainType.id} null ${pad} -1\n"
    }
  }

  private def loop(w: Writer, mod: String, path: String, daisyWidth: Int)(chainType: ChainType.Value) {
    chains(chainType) get mod match {
      case Some(chain) if !chain.isEmpty =>
        val (cw, dw) = (chain foldLeft (0, 0)){case ((chainWidth, dataWidth), s) =>
          val dw = dataWidth + (s match {
            case s: WDefInstance =>
              val seqMem = seqMems(s.module)
              val id = chainType.id
              val prefix = s"$path.${seqMem.name}"
              chainType match {
                case ChainType.SRAM =>
                  w write s"$id $prefix.ram ${seqMem.width} ${seqMem.depth}\n"
                  seqMem.width.toInt
                case _ =>
                  val addrWidth = chisel3.util.log2Up(seqMem.depth.toInt)
                  seqMem.readers.indices foreach (i =>
                    w write s"$id $prefix.reg_R$i $addrWidth -1\n")
                  seqMem.readwriters.indices foreach (i =>
                    w write s"$id $prefix.reg_RW$i $addrWidth -1\n")
                  /* seqMem.readers.indices foreach (i =>
                    w write s"$id $prefix ${seqMem.width} -1\n")
                  seqMem.readwriters.indices foreach (i =>
                    w write s"$id $prefix ${seqMem.width} -1\n") */
                  (seqMem.readers.size + seqMem.readwriters.size) * (addrWidth /*+ seqMem.width.toInt*/)
              }
            case s: DefMemory => (create_exps(s.name, s.dataType) foldLeft 0){ (totalWidth, mem) =>
              val name = verilogRenameN(loweredName(mem))
              val width = bitWidth(mem.tpe).toInt
              chainType match {
                case ChainType.SRAM =>
                  w write s"${chainType.id} $path.$name $width ${s.depth}\n"
                  totalWidth + width
                case _ => totalWidth + (((0 until s.depth) foldLeft 0){ (memWidth, i) =>
                  w write s"${chainType.id} $path.$name[$i] $width -1\n"
                  memWidth + width
                })
              }
            }
            case s: DefRegister => (create_exps(s.name, s.tpe) foldLeft 0){ (totalWidth, reg) =>
              val name = verilogRenameN(loweredName(reg))
              val width = bitWidth(reg.tpe).toInt
              w write s"${chainType.id} $path.$name $width -1\n"
              totalWidth + width
            }
          })
          val cw = (Stream from 0 map (chainWidth + _ * daisyWidth) dropWhile (_ < dw)).head
          chainType match {
            case ChainType.SRAM => 
              addPad(w, cw, dw)(chainType)
              (0, 0)
            case _ => (cw, dw)
          }
        }
        chainType match {
          case ChainType.SRAM => 
          case _ => addPad(w, cw, dw)(chainType)
        }
      case _ =>
    }
    childInsts(mod) foreach (child =>
      loop(w, instModMap(child, mod), s"${path}.${child}", daisyWidth)(chainType))
  }

  private object TraceType extends Enumeration {
    val InTr = Value(ChainType.values.size)
    val OutTr = Value(ChainType.values.size + 1)
  }

  private def dumpTraceMap(ioMap: Map[chisel3.Bits, String],
                           trType: TraceType.Value)
                          (arg: (chisel3.Bits, Int))
                          (implicit channelWidth: Int) = arg match {
    case (wire, id) => s"${trType.id} ${ioMap(wire)} ${id} ${SimUtils.getChunks(wire)}\n"
  }

  private def dumpChains(c: ZynqShim[_], target: String) {
    val sim = c.sim match { case sim: SimWrapper => sim }
    val ioMap = (sim.io.inputs ++ sim.io.outputs).toMap
    implicit val channelWidth = sim.channelWidth
    val file = new File(dir, s"$target.chain")
    val writer = new FileWriter(file)
    val sb = new StringBuilder
    ChainType.values.toList foreach loop(writer, target, target, sim.daisyWidth)
    c.IN_TR_ADDRS map dumpTraceMap(ioMap, TraceType.InTr) addString sb
    c.OUT_TR_ADDRS map dumpTraceMap(ioMap, TraceType.OutTr) addString sb
    writer write sb.result
    writer.close
  }

  private def dumpHeader(c: ZynqShim[_], target: String) {
    val sim = c.sim match { case sim: SimWrapper => sim }
    val nameMap = (sim.io.inputs ++ sim.io.outputs).toMap
    implicit val channelWidth = sim.channelWidth
    def dump(arg: (String, Int)): String =
      s"#define ${arg._1} ${arg._2}\n"
    def dumpId(arg: (Bits, Int)): String =
      s"#define ${nameMap(arg._1)} ${arg._2}\n"
    def dumpNames(arg: (Bits, Int)): Seq[String] = {
      val chunks = SimUtils.getChunks(arg._1)
      (0 until chunks) map (i => if (i == 0) "  \"%s\"".format(nameMap(arg._1)) else "  \"\"")
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
      "DAISY_WIDTH"       -> sim.daisyWidth,
      "TRACE_MAX_LEN"     -> sim.traceMaxLen,
      "CHANNEL_SIZE"      -> chisel3.util.log2Up(c.master.nastiXDataBits/8),
      "MEM_DATA_CHUNK"    -> SimUtils.getChunks(c.io.slave.w.bits.data),

      "SRAM_RESTART_ADDR" -> c.SRAM_RESTART_ADDR,
      "MEM_AR_ADDR"       -> c.AR_ADDR,
      "MEM_AW_ADDR"       -> c.AW_ADDR,
      "MEM_W_ADDR"        -> c.W_ADDR,
      "MEM_R_ADDR"        -> c.R_ADDR
    )
    val csb = new StringBuilder
    csb append "#ifndef __%s_H\n".format(target.toUpperCase)
    csb append "#define __%s_H\n".format(target.toUpperCase)
    csb append "static const char* const TARGET_NAME = \"%s\";\n".format(target)
    if (sim.enableSnapshot) csb append "#define ENABLE_SNAPSHOT\n"
    consts map dump addString csb
    csb append "// IDs assigned to I/Os\n"
    c.IN_ADDRS map dumpId addString csb
    c.OUT_ADDRS map dumpId addString csb
    c.genHeader(csb)
    csb append "enum CHAIN_TYPE {%s,CHAIN_NUM};\n".format(
      ChainType.values.toList map (t => s"${t.toString.toUpperCase}_CHAIN") mkString ",")
    csb append "static const unsigned CHAIN_SIZE[CHAIN_NUM] = {%s};\n".format(
      ChainType.values.toList map (t => c.master.io.daisy(t).size) mkString ",")
    csb append "static const unsigned CHAIN_ADDR[CHAIN_NUM] = {%s};\n".format(
      ChainType.values.toList map c.DAISY_ADDRS mkString ",")
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

  private def initializeTargetStmt(mname: String)(s: Statement): Statement =
    s match {
      case s: WDefInstance if s.name == "target" && s.module == "TargetBox" =>
        s copy (module = mname) // replace TargetBox with the actual target module
      case s => s map initializeTargetStmt(mname)
    }

  private def initializeTarget(info: Info, mname: String)(m: DefModule) = m match {
    case m: Module if m.name == "SimWrapper" =>
      val body = initializeTargetStmt(mname)(m.body)
      val stmts = Seq(
        Connect(NoInfo, wsub(wref("target"), "targetFire"), wref("fire", BoolType)),
        Connect(NoInfo, wsub(wref("target"), "daisyReset"), wref("reset", BoolType))) ++
      (if (!param(EnableSnapshot)) Nil else Seq(
        Connect(NoInfo, wsub(wref("io"), "daisy"), wsub(wref("target"), "daisy"))))
      m copy (info = info, body = Block(body +: stmts))
    case m => m
  }

  def run(c: Circuit) = {
    lazy val shim = ZynqShim(io)
    val chirrtl = Parser parse (chisel3.Driver emit (() => shim))
    val annotations = new Annotations.AnnotationMap(Nil)
    val writer = new StringWriter
    // val writer = new FileWriter(new File("ZynqShim-debug.ir"))
    val circuit = (new WrappingCompiler compile (chirrtl, annotations, writer)).circuit
    // writer.close
    dumpHeader(shim, c.main)
    dumpChains(shim, c.main)
    circuit copy (modules = c.modules ++ (circuit.modules
      map initializeTarget(c.info, c.main) collect { case m: Module => m }))
  }
}
