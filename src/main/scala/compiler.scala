package strober

import chisel3.{Data, Bits}
import firrtl.Annotations.{AnnotationMap, TransID}
import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet}
import scala.util.DynamicVariable
import scala.reflect.ClassTag
import java.io.{File, FileWriter, Writer}

private class StroberCompilerContext {
  // Todo: Should be handled in the backend
  val memPorts = ArrayBuffer[junctions.NastiIO]()
  val memWires = HashSet[Bits]()
}

private class StroberCompiler(dir: File, io: Data)(implicit param: cde.Parameters) extends firrtl.Compiler {
  def transforms(writer: Writer): Seq[firrtl.Transform] = Seq(
    new firrtl.Chisel3ToHighFirrtl,
    new firrtl.IRToWorkingIR,
    new firrtl.ResolveAndCheck,
    new firrtl.HighFirrtlToMiddleFirrtl,
    new firrtl.passes.InferReadWrite(TransID(-1)),
    new firrtl.passes.memlib.ReplSeqMem(TransID(-2)),
    new passes.StroberTransforms(dir, io),
    new firrtl.EmitFirrtl(writer)
  )
}

// This compiler compiles HighFirrtl To verilog
private class VerilogCompiler(conf: File) extends firrtl.Compiler {
  def transforms(writer: Writer): Seq[firrtl.Transform] = Seq(
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

  def apply[T <: chisel3.Module](w: => T, dir: File)(implicit p: cde.Parameters) = {
    (contextVar withValue Some(new StroberCompilerContext)){
      dir.mkdirs
      lazy val target = w
      val chirrtl = firrtl.Parser.parse(chisel3.Driver.emit(() => target))
      val conf = new File(dir, s"${chirrtl.main}.conf")
      val annotations = new AnnotationMap(Seq(
        firrtl.passes.InferReadWriteAnnotation(chirrtl.main, TransID(-1)),
        firrtl.passes.memlib.ReplSeqMemAnnotation(s"-c:${chirrtl.main}:-o:$conf", TransID(-2))))
      val writer = new FileWriter(new File("debug.ir"))
      // val writer = new java.io.StringWriter
      val strober = (new StroberCompiler(dir, target.io)
        compile (chirrtl, annotations, writer)).circuit
      writer.close
      // firrtl.Parser.parse(writer.toString)
      val verilog = new FileWriter(new File(dir, s"${strober.main}.v"))
      val result = new VerilogCompiler(conf) compile (strober, annotations, verilog)
      verilog.close
      result
    }
  }
}
