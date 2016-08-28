package strober

import firrtl.Annotations.{AnnotationMap, TransID}
import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet}
import scala.util.DynamicVariable
import java.io.{File, FileWriter}

private class CompilerContext {
  var dir = new File("test-outs")
  var sampleNum = 30
  val wrappers = HashSet[String]()
  val params = HashMap[String, cde.Parameters]()
  val shims = ArrayBuffer[ZynqShim[_]]()
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

class StroberCompiler extends firrtl.Compiler {
  def transforms(writer: java.io.Writer): Seq[firrtl.Transform] = Seq(
    new firrtl.Chisel3ToHighFirrtl,
    new firrtl.IRToWorkingIR,
    new firrtl.ResolveAndCheck,
    new firrtl.HighFirrtlToMiddleFirrtl,
    new firrtl.passes.InferReadWrite(TransID(-1)),
    passes.StroberTransforms,
    new firrtl.EmitFirrtl(writer)
  )
}

object StroberCompiler {
  private val contextVar = new DynamicVariable[Option[CompilerContext]](None)
  private[strober] def context = contextVar.value.getOrElse (
    throw new Exception("StroberCompiler should be properly used"))

  private def dumpIoMap(c: ZynqShim[_]) {
    object MapType extends Enumeration { val IoIn, IoOut, InTr, OutTr = Value }
    val sim = c.sim match { case sim: SimWrapper[_] => sim }
    val targetName = sim.target.name
    val nameMap = (sim.io.inputs ++ sim.io.outputs).toMap
    def dump(map_t: MapType.Value, arg: (chisel3.Bits, Int)) = arg match {case (wire, id) =>
      s"${map_t.id} ${targetName}.${nameMap(wire)} ${id} ${SimUtils.getChunks(wire)(sim.channelWidth)}\n"} 
    val sb = new StringBuilder
    sb append (c.IN_ADDRS  map (x => (x._1, x._2 - c.CTRL_NUM)) map {dump(MapType.IoIn,  _)} mkString "")
    sb append (c.OUT_ADDRS map (x => (x._1, x._2 - c.CTRL_NUM)) map {dump(MapType.IoOut, _)} mkString "")

    val file = new FileWriter(new File(context.dir, s"${targetName}.map"))
    try {
      file write sb.result
    } finally {
      file.close
      sb.clear
    }
  }

  private def dumpHeader(c: ZynqShim[_]) {
    val sim = c.sim match { case sim: SimWrapper[_] => sim }
    val targetName = sim.target.name
    implicit val channelWidth = sim.channelWidth
    def dump(arg: (String, Int)) = s"#define ${arg._1} ${arg._2}\n"
    val consts = List(
      "HOST_RESET_ADDR"    -> ZynqCtrlSignals.HOST_RESET.id,
      "SIM_RESET_ADDR"     -> ZynqCtrlSignals.SIM_RESET.id,
      "STEP_ADDR"          -> ZynqCtrlSignals.STEP.id,
      "DONE_ADDR"          -> ZynqCtrlSignals.DONE.id,
      "TRACELEN_ADDR"      -> ZynqCtrlSignals.TRACELEN.id,
      "LATENCY_ADDR"       -> ZynqCtrlSignals.LATENCY.id,
      "MEM_AR_ADDR"        -> c.AR_ADDR,
      "MEM_AW_ADDR"        -> c.AW_ADDR,
      "MEM_W_ADDR"         -> c.W_ADDR,
      "MEM_R_ADDR"         -> c.R_ADDR,
      "CTRL_NUM"           -> c.CTRL_NUM,
      "POKE_SIZE"          -> c.ins.size,
      "PEEK_SIZE"          -> c.outs.size,
      "MEM_DATA_BITS"      -> c.arb.nastiXDataBits,
      "TRACE_MAX_LEN"      -> sim.traceMaxLen,
      "MEM_DATA_CHUNK"     -> SimUtils.getChunks(c.io.slave.w.bits.data)
    )
    val sb = new StringBuilder
    sb append "#ifndef __%s_H\n".format(targetName.toUpperCase)
    sb append "#define __%s_H\n".format(targetName.toUpperCase)
    consts foreach (sb append dump(_))
    sb append "#endif  // __%s_H\n".format(targetName.toUpperCase)
    val file = new FileWriter(new File(context.dir, s"${targetName}-const.h"))
    try {
      file write sb.result
    } finally {
      file.close
      sb.clear
    }
  }

  private[strober] def annotate(sim: SimWrapper[_]) = {
    context.wrappers += sim.name
    context.params(sim.name) = sim.p
  }

  private[strober] def annotate(shim: ZynqShim[_]) = {
    context.shims += shim
  }

  private def parseArgs(args: List[String]): Unit = args match {
    case Nil =>
    case "--targetDir" :: value :: tail =>
      context.dir = new File(value)
      context.dir.mkdirs
    case "--sampleNum" :: value :: tail =>
      context.sampleNum = value.toInt
    case head :: tail => parseArgs(tail)
  }

  private def transform[T <: chisel3.Module](w: => T) = {
    val chirrtl = firrtl.Parser.parse(chisel3.Driver.emit(() => w))
    val annotations = new AnnotationMap(Seq(
      new firrtl.passes.InferReadWriteAnnotation(chirrtl.main, TransID(-1))))
    val writer = new java.io.StringWriter
    new StroberCompiler compile (chirrtl, annotations, writer)
    firrtl.Parser.parse(writer.toString)
  }

  private def compile(circuit: firrtl.ir.Circuit) {
    // Dump meta data
    context.shims foreach dumpIoMap
    context.shims foreach dumpHeader
    // Compile Verilog
    val verilog = new FileWriter(new File(context.dir, s"${circuit.main}.v"))
    new firrtl.VerilogCompiler compile (circuit, new AnnotationMap(Nil), verilog)
    verilog.close
  }

  def compile[T <: chisel3.Module](args: Array[String], w: => T) {
    (contextVar withValue Some(new CompilerContext)){
      parseArgs(args.toList)
      compile(transform(w))
    }
  }

  def apply[T <: chisel3.Module](args: Array[String], w: => T, backend: String = "verilator")(
      tester: T => testers.StroberTester[T]) {
    (contextVar withValue Some(new CompilerContext)) {
      parseArgs(args.toList)
      compile(transform(w))
      val testerArgs = Array("--targetDir", context.dir.toString,
        "--backend", backend, "--genHarness", "--compile", "--test", "--vpdmem")
      chisel3.iotesters.chiselMainTest(testerArgs, () => w)(tester)
    }
  }

  def compile[T <: chisel3.Module](args: Array[String], w: => T, backend: String) {
    (contextVar withValue Some(new CompilerContext)){
      parseArgs(args.toList)
      compile(transform(w))
      val testerArgs = Array("--targetDir", context.dir.toString,
        "--backend", backend, "--genHarness", "--compile", "--test")
      chisel3.iotesters.chiselMain(testerArgs, () => w)
    }
  }

  def test[T <: chisel3.Module](args: Array[String], w: => T, backend: String = "verilator")(
      tester: T => testers.StroberTester[T]) {
    (contextVar withValue Some(new CompilerContext)){
      parseArgs(args.toList)
      transform(w)
      val testerArgs = Array("--targetDir", context.dir.toString,
        "--backend", backend, "--test")
      chisel3.iotesters.chiselMainTest(testerArgs, () => w)(tester)
    }
  }
}
