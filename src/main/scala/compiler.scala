package strober

import firrtl._
import firrtl.passes._
import Annotations.{AnnotationMap, TransID}
import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet}
import scala.collection.immutable.ListSet
import scala.util.DynamicVariable

private class TransformContext {
  val shims = HashSet[String]()
  val wrappers = HashSet[String]()
  val params = HashMap[String, cde.Parameters]()
  val childInsts = HashMap[String, ListSet[String]]()
  val childMods = HashMap[String, ListSet[String]]()
  val instToMod = HashMap[String, String]()
  val chains = Map(
    ChainType.Trace -> HashMap[String, Seq[ir.Statement]](),
    ChainType.Regs  -> HashMap[String, Seq[ir.Statement]](),
    ChainType.SRAM  -> HashMap[String, Seq[ir.Statement]](),
    ChainType.Cntr  -> HashMap[String, Seq[ir.Statement]]()
  )
  val chainLen = HashMap(
    ChainType.Trace -> 0,
    ChainType.Regs  -> 0,
    ChainType.SRAM  -> 0,
    ChainType.Cntr  -> 0)
  val chainLoop = HashMap(
    ChainType.Trace -> 0,
    ChainType.Regs  -> 0,
    ChainType.SRAM  -> 0,
    ChainType.Cntr  -> 0)
}

class StroberTransforms(chain: java.io.Writer) extends Transform with SimpleRun {
  val passSeq = Seq(
    strober.passes.Analyses,
    strober.passes.Fame1Transform,
    strober.passes.AddDaisyChains,
    new strober.passes.DumpChains(chain)
  )
  def execute(circuit: ir.Circuit, annotationMap: AnnotationMap) =
    run(circuit, passSeq)
}

class StroberCompiler(chain: java.io.Writer) extends Compiler {
  def transforms(writer: java.io.Writer): Seq[Transform] = Seq(
    new Chisel3ToHighFirrtl,
    new IRToWorkingIR,
    new ResolveAndCheck,
    new HighFirrtlToMiddleFirrtl,
    new InferReadWrite(TransID(-1)),
    new StroberTransforms(chain),
    new EmitFirrtl(writer)
  )
}

// Meta data for testers
case class StroberMetaData(
  chainFile: java.io.File,
  chainLoop: Map[ChainType.Value, Int],
  chainLen: Map[ChainType.Value, Int],
  sampleNum: Int = 30
)

object StroberCompiler {
  private val contextVar = new DynamicVariable[Option[TransformContext]](None)
  private[strober] def context = contextVar.value.getOrElse (new TransformContext)

  private def dumpIoMap[T <: SimNetwork](c: ZynqShim[T], targetName: String) {
    object MapType extends Enumeration { val IoIn, IoOut, InTr, OutTr = Value }
    val nameMap = (c.sim.io.inputs ++ c.sim.io.outputs).toMap
    def dump(map_t: MapType.Value, arg: (chisel3.Bits, Int)) = arg match {case (wire, id) =>
      s"${map_t.id} ${targetName}.${nameMap(wire)} ${id} ${SimUtils.getChunks(wire)(c.sim.channelWidth)}\n"} 
    val sb = new StringBuilder
    sb append (c.IN_ADDRS map (x => (x._1, x._2 - c.CTRL_NUM))  map {dump(MapType.IoIn,  _)} mkString "")
    sb append (c.OUT_ADDRS map (x => (x._1, x._2 - c.CTRL_NUM)) map {dump(MapType.IoOut, _)} mkString "")

    val file = new java.io.FileWriter(
      new java.io.File(chisel3.Driver.targetDir, s"${targetName}.map"))
    try {
      file write sb.result
    } finally {
      file.close
      sb.clear
    }
  }

  private def dumpHeader[T <: SimNetwork](c: ZynqShim[T], targetName: String) {
    implicit val channelWidth = c.sim.channelWidth
    def dump(arg: (String, Int)) = s"#define ${arg._1} ${arg._2}\n"
    val consts = List(
      "RESET_ADDR"         -> c.RESET_ADDR,
      "STEP_ADDR"          -> c.STEP_ADDR,
      "DONE_ADDR"          -> c.DONE_ADDR,
      "TRACELEN_ADDR"      -> c.TRACELEN_ADDR,
      "LATENCY_ADDR"       -> c.LATENCY_ADDR,
      "MEM_AR_ADDR"        -> c.AR_ADDR,
      "MEM_AW_ADDR"        -> c.AW_ADDR,
      "MEM_W_ADDR"         -> c.W_ADDR,
      "MEM_R_ADDR"         -> c.R_ADDR,
      "CTRL_NUM"           -> c.CTRL_NUM,
      "POKE_SIZE"          -> c.ins.size,
      "PEEK_SIZE"          -> c.outs.size,
      "TRACE_MAX_LEN"      -> c.sim.traceMaxLen,
      "MEM_DATA_BITS"      -> c.arb.nastiXDataBits,
      "MEM_DATA_CHUNK"     -> SimUtils.getChunks(c.io.slave.w.bits.data)
    )
    val sb = new StringBuilder
    sb append "#ifndef __%s_H\n".format(targetName.toUpperCase)
    sb append "#define __%s_H\n".format(targetName.toUpperCase)
    consts foreach (sb append dump(_))
    sb append "#endif  // __%s_H\n".format(targetName.toUpperCase)
    val file = new java.io.FileWriter(
      new java.io.File(chisel3.Driver.targetDir, s"${targetName}-const.h"))
    try {
      file write sb.result
    } finally {
      file.close
      sb.clear
    }
  }

  def apply[T <: chisel3.Module](args: Array[String], w: => T) = {
    contextVar.withValue(Some(new TransformContext)) {
      // Generate Chirrtl
      chisel3.Driver.parseArgs(args)
      val circuit = chisel3.Driver.elaborate(() => w)
      val dir = new java.io.File(chisel3.Driver.targetDir) ; dir.mkdirs
      val chirrtl = new java.io.File(dir, s"${circuit.name}.ir")
      chisel3.Driver.dumpFirrtl(circuit, Some(chirrtl))
      val dut = (circuit.components find (_.name == circuit.name)).get.id
      // Run Strober Custom Passes
      val targetName = dut match {
        case w: SimWrapper[_] =>
          context.wrappers += w.name
          context.params(w.name) = w.p
          w.target.name
        case w: ZynqShim[_] =>
          context.shims += w.name
          context.wrappers += w.sim.name
          context.params(w.sim.name) = w.sim.p
          w.sim match { case w: SimWrapper[_] => w.target.name }
      }
      val chain = new java.io.File(dir, s"${targetName}.chain")
      val firrtl = new java.io.File(dir, s"${circuit.name}-strober.fir")
      val annotations = new AnnotationMap(Seq(new InferReadWriteAnnotation(circuit.name, TransID(-1))))
      Driver.compile(chirrtl.toString, firrtl.toString,
        new StroberCompiler(new java.io.FileWriter(chain)), annotations=annotations)
      // Dump meta data
      dut match {
        case w: ZynqShim[_] =>
          dumpIoMap(w, targetName)
          dumpHeader(w, targetName)
        case _ =>
      }
      // Compile Verilog
      val verilog = new java.io.File(dir, s"${circuit.name}.v")
      Driver.compile(firrtl.toString, verilog.toString, new VerilogCompiler)
      (circuit, StroberMetaData(chain, context.chainLoop.toMap, context.chainLen.toMap))
    }
  }
}
