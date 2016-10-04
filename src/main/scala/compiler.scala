package strober

import chisel3.iotesters
import firrtl.Annotations.{AnnotationMap, TransID}
import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet}
import scala.util.DynamicVariable
import scala.reflect.ClassTag
import java.io.{File, FileWriter}

private class StroberCompilerContext {
  var dir = chisel3.Driver.createTempDirectory("test-outs")
  var sampleNum = 30
  val wrappers = HashSet[String]()
  val params = HashMap[String, cde.Parameters]()
  val shims = ArrayBuffer[ZynqShim[_]]()
  // Todo: Should be handled in the backend
  val memPorts = ArrayBuffer[junctions.NastiIO]()
  val memWires = HashSet[chisel3.Bits]()
}

private class StroberCompiler extends firrtl.Compiler {
  def transforms(writer: java.io.Writer): Seq[firrtl.Transform] = Seq(
    new firrtl.Chisel3ToHighFirrtl,
    new firrtl.IRToWorkingIR,
    new firrtl.ResolveAndCheck,
    new firrtl.HighFirrtlToMiddleFirrtl,
    new firrtl.passes.InferReadWrite(TransID(-1)),
    new firrtl.passes.ReplSeqMem(TransID(-2)),
    passes.StroberTransforms,
    new firrtl.EmitFirrtl(writer)
  )
}

// This compiler compiles HighFirrtl To verilog
private class VerilogCompiler(conf: java.io.File) extends firrtl.Compiler {
  def transforms(writer: java.io.Writer): Seq[firrtl.Transform] = Seq(
    new firrtl.ResolveAndCheck,
    new firrtl.HighFirrtlToMiddleFirrtl,
    new firrtl.MiddleFirrtlToLowFirrtl,
    new firrtl.EmitVerilogFromLowFirrtl(writer),
    new passes.EmitMemFPGAVerilog(writer, conf)
  )
}

object StroberCompiler {
  private val contextVar = new DynamicVariable[Option[StroberCompilerContext]](None)
  private[strober] def context = contextVar.value.getOrElse (
    throw new Exception("StroberCompiler should be properly used"))

  private def dumpIoMap(c: ZynqShim[_]) {
    object MapType extends Enumeration { val IoIn, IoOut, InTr, OutTr = Value }
    val sim = c.sim match { case sim: SimWrapper[_] => sim }
    val targetName = sim.target.name
    val nameMap = (sim.io.inputs ++ sim.io.outputs).toMap
    def dump(map_t: MapType.Value)(arg: (chisel3.Bits, Int)) = arg match {case (wire, id) =>
      s"${map_t.id} ${targetName}.${nameMap(wire)} ${id} ${SimUtils.getChunks(wire)(sim.channelWidth)}\n"} 
    val sb = new StringBuilder
    c.IN_TR_ADDRS map dump(MapType.InTr) addString sb
    c.OUT_TR_ADDRS map dump(MapType.OutTr) addString sb

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
    val nameMap = (sim.io.inputs ++ sim.io.outputs).toMap
    implicit val channelWidth = sim.channelWidth
    def dump(arg: (String, Int)): String =
      s"#define ${arg._1} ${arg._2}\n"
    def dumpId(arg: (chisel3.Bits, Int)): String =
      s"#define ${nameMap(arg._1)} ${arg._2 - c.CTRL_NUM}\n"
    def dumpNames(arg: (chisel3.Bits, Int)): Seq[String] = {
      val chunks = SimUtils.getChunks(arg._1)
      (0 until chunks) map (i => if (i == 0) "  \"%s\"".format(nameMap(arg._1)) else "  \"\"")
    }
    def dumpChunks(arg: (chisel3.Bits, Int)): Seq[Int] = {
      val chunks = SimUtils.getChunks(arg._1)
      (0 until chunks) map (i => if (i == 0) chunks else 0)
    }

    val consts = List(
      "CTRL_NUM"          -> c.CTRL_NUM,
      "ENABLE_SNAPSHOT"   -> (c.sim match { case sim: SimWrapper[_] => if (sim.enableSnapshot) 1 else 0 }),
      "DAISY_WIDTH"       -> (c.sim match { case sim: SimWrapper[_] => sim.daisyWidth }),
      "POKE_SIZE"         -> c.ins.size,
      "PEEK_SIZE"         -> c.outs.size,
      "MEM_DATA_BITS"     -> c.arb.nastiXDataBits,
      "TRACE_MAX_LEN"     -> sim.traceMaxLen,
      "MEM_DATA_CHUNK"    -> SimUtils.getChunks(c.io.slave.w.bits.data),

      "HOST_RESET_ADDR"   -> ZynqCtrlSignals.HOST_RESET.id,
      "SIM_RESET_ADDR"    -> ZynqCtrlSignals.SIM_RESET.id,
      "STEP_ADDR"         -> ZynqCtrlSignals.STEP.id,
      "DONE_ADDR"         -> ZynqCtrlSignals.DONE.id,
      "TRACELEN_ADDR"     -> ZynqCtrlSignals.TRACELEN.id,
      "SRAM_RESTART_ADDR" -> c.SRAM_RESTART_ADDR,
      "MEM_AR_ADDR"       -> c.AR_ADDR,
      "MEM_AW_ADDR"       -> c.AW_ADDR,
      "MEM_W_ADDR"        -> c.W_ADDR,
      "MEM_R_ADDR"        -> c.R_ADDR
    )
    val sb = new StringBuilder
    sb append "#ifndef __%s_H\n".format(targetName.toUpperCase)
    sb append "#define __%s_H\n".format(targetName.toUpperCase)
    sb append "const char* const TARGET_NAME = \"%s\";\n".format(targetName)
    consts map dump addString sb
    sb append "// IDs assigned to I/Os\n"
    c.IN_ADDRS map dumpId addString sb
    c.OUT_ADDRS map dumpId addString sb
    c.genHeader(sb)
    sb append "enum CHAIN_TYPE {%s,CHAIN_NUM};\n".format(
      ChainType.values.toList map (t => s"${t.toString.toUpperCase}_CHAIN") mkString ",")
    sb append "const unsigned CHAIN_SIZE[CHAIN_NUM] = {%s};\n".format(
      ChainType.values.toList map (t => c.master.io.daisy(t).size) mkString ",")
    sb append "const unsigned CHAIN_ADDR[CHAIN_NUM] = {%s};\n".format(
      ChainType.values.toList map c.DAISY_ADDRS mkString ",")
    sb append "const char* const INPUT_NAMES[POKE_SIZE] = {\n%s\n};\n".format(
      c.IN_ADDRS flatMap dumpNames mkString ",\n")
    sb append "const char* const OUTPUT_NAMES[PEEK_SIZE] = {\n%s\n};\n".format(
      c.OUT_ADDRS flatMap dumpNames mkString ",\n")
    sb append "const unsigned INPUT_CHUNKS[POKE_SIZE] = {%s};\n".format(
      c.IN_ADDRS flatMap dumpChunks mkString ",")
    sb append "const unsigned OUTPUT_CHUNKS[PEEK_SIZE] = {%s};\n".format(
      c.OUT_ADDRS flatMap dumpChunks mkString ",")
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
    val conf = new File(context.dir, s"${chirrtl.main}.conf")
    val annotations = new AnnotationMap(Seq(
      firrtl.passes.InferReadWriteAnnotation(chirrtl.main, TransID(-1)),
      firrtl.passes.ReplSeqMemAnnotation(s"-c:${chirrtl.main}:-o:$conf", TransID(-2))))
    // val writer = new FileWriter(new File("debug.ir"))
    val writer = new java.io.StringWriter
    val result = new StroberCompiler compile (chirrtl, annotations, writer)
    // writer.close
    // firrtl.Parser.parse(writer.toString)
    (result.circuit, conf)
  }

  private def compile(circuit: firrtl.ir.Circuit, conf: File): firrtl.ir.Circuit = {
    // Dump meta data
    context.shims foreach dumpHeader
    // Compile Verilog
    val annotations = new AnnotationMap(Nil)
    val verilog = new FileWriter(new File(context.dir, s"${circuit.main}.v"))
    val result = new VerilogCompiler(conf) compile (circuit, annotations, verilog)
    verilog.close
    result.circuit
  }

  def compile[T <: chisel3.Module](args: Array[String], w: => T): firrtl.ir.Circuit = {
    (contextVar withValue Some(new StroberCompilerContext)){
      parseArgs(args.toList)
      val (circuit, conf) = transform(w)
      compile(circuit, conf)
    }
  }

  def compile[T <: chisel3.Module](args: Array[String],
                                   w: => T,
                                   backend: String = "verilator"): T = {
    (contextVar withValue Some(new StroberCompilerContext)) {
      parseArgs(args.toList)
      val (circuit, conf) = transform(w)
      compile(circuit, conf)
      val testerArgs = Array("--targetDir", context.dir.toString,
        "--backend", backend, "--genHarness", "--compile")
      iotesters.chiselMain(testerArgs, () => w)
    }
  }

  def apply[T <: chisel3.Module](args: Array[String],
                                 w: => T,
                                 backend: String = "verilator")
                                 (tester: T => testers.StroberTester[T]): T = {
    (contextVar withValue Some(new StroberCompilerContext)) {
      parseArgs(args.toList)
      val (circuit, conf) = transform(w)
      val c = compile(circuit, conf)
      val log = new File(context.dir, s"${c.main}.log")
      val targs = Array(
        "--targetDir", context.dir.toString,
        "--logFile", log.toString,
        "--backend", backend,
        "--genHarness", "--compile", "--test" /*, "--vpdmem"*/)
      iotesters.chiselMainTest(targs, () => w)(tester)
    }
  }

  def test[T <: chisel3.Module](args: Array[String],
                                w: => T,
                                backend: String = "verilator",
                                waveform: Option[File] = None)
                               (tester: T => testers.StroberTester[T]) = {
    (contextVar withValue Some(new StroberCompilerContext)) {
      parseArgs(args.toList)
      val (circuit, conf) = transform(w)
      val c = compile(circuit, conf)
      val cmd = new File(context.dir, backend match {
        case "verilator" => s"V${c.main}" case _ => c.main
      })
      iotesters.Driver.run(() => w, cmd, waveform)(tester)
    }
  }
}


private class ReplayCompilerContext(target: String) {
  var dir = chisel3.Driver.createTempDirectory("test-outs")
  var sample = new File("${target}.sample")
}

private class ReplayCompiler(conf: File) extends firrtl.Compiler {
  def transforms(writer: java.io.Writer): Seq[firrtl.Transform] = Seq(
    new firrtl.Chisel3ToHighFirrtl,
    new firrtl.IRToWorkingIR,
    new firrtl.ResolveAndCheck,
    new firrtl.HighFirrtlToMiddleFirrtl,
    new firrtl.passes.InferReadWrite(TransID(-1)),
    new firrtl.passes.ReplSeqMem(TransID(-2)),
    new firrtl.MiddleFirrtlToLowFirrtl,
    new firrtl.EmitVerilogFromLowFirrtl(writer),
    new passes.EmitMemFPGAVerilog(writer, conf)
  )
}

object ReplayCompiler {
  private val contextVar = new DynamicVariable[Option[ReplayCompilerContext]](None)
  private[strober] def context = contextVar.value.getOrElse (
    throw new Exception("StroberCompiler should be properly used"))

  private def parseArgs(args: List[String]): Unit = args match {
    case Nil =>
    case "--targetDir" :: value :: tail =>
      context.dir = new File(value)
      context.dir.mkdirs
    case "--sample" :: value :: tail =>
      context.sample = new File(value)
    case head :: tail => parseArgs(tail)
  }

  private def compile[T <: chisel3.Module](w: => T) = {
    val chirrtl = firrtl.Parser.parse(chisel3.Driver.emit(() => w))
    val conf = new File(context.dir, s"${chirrtl.main}.conf")
    val annotations = new AnnotationMap(Seq(
      firrtl.passes.InferReadWriteAnnotation(chirrtl.main, TransID(-1)),
      firrtl.passes.ReplSeqMemAnnotation(s"-c:${chirrtl.main}:-o:$conf", TransID(-2))))
    val verilog = new FileWriter(new File(context.dir, s"${chirrtl.main}.v"))
    val result = new ReplayCompiler(conf) compile (chirrtl, annotations, verilog)
    verilog.close
    result.circuit
  }

  def compile[T <: chisel3.Module : ClassTag](
      args: Array[String],
      w: => T): firrtl.ir.Circuit = {
    val target = implicitly[ClassTag[T]].runtimeClass.getSimpleName
    (contextVar withValue Some(new ReplayCompilerContext(target))){
      parseArgs(args.toList)
      compile(w)
    }
  }

  def compile[T <: chisel3.Module : ClassTag](
      args: Array[String],
      w: => T,
      backend: String): T = {
    val target = implicitly[ClassTag[T]].runtimeClass.getSimpleName
    (contextVar withValue Some(new ReplayCompilerContext(target))){
      parseArgs(args.toList)
      val c = compile(w)
      val testerArgs = Array("--targetDir", context.dir.toString,
        "--backend", backend, "--genHarness", "--compile", "--test")
      iotesters.chiselMain(testerArgs, () => w)
    }
  }

  def apply[T <: chisel3.Module : ClassTag](
      args: Array[String],
      w: => T,
      backend: String = "verilator",
      waveform: Option[File] = None)
      (tester: T => testers.Replay[T]): T = {
    val target = implicitly[ClassTag[T]].runtimeClass.getSimpleName
    (contextVar withValue Some(new ReplayCompilerContext(target))){
      parseArgs(args.toList)
      val c = compile(w)
      val log = new File(context.dir, s"${c.main}.log")
      val targs = Array(
        "--targetDir", context.dir.toString,
        "--logFile", log.toString,
        "--backend", backend,
        "--genHarness", "--compile", "--test"/*, "--vpdmem"*/) ++
        (waveform match {
          case None => Array[String]()
          case Some(f) => Array("--waveform", f.toString)
        })
      iotesters.chiselMainTest(targs, () => w)(tester)
    }
  }

  def test[T <: chisel3.Module : ClassTag](
      args: Array[String],
      w: => T,
      backend: String = "verilator",
      waveform: Option[File] = None)
      (tester: T => testers.Replay[T]) = {
    val target = implicitly[ClassTag[T]].runtimeClass.getSimpleName
    (contextVar withValue Some(new ReplayCompilerContext(target))){
      parseArgs(args.toList)
      val c = compile(w)
      val cmd = new File(context.dir, backend match {
        case "verilator" => s"V${c.main}" case _ => c.main
      })
      iotesters.Driver.run(() => w, cmd, waveform)(tester)
    }
  }
}

