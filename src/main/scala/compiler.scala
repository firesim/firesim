package strober

import chisel3.{Data, Bits}
import firrtl.ir.Circuit
import firrtl.Annotations.{AnnotationMap, TransID}
import firrtl.passes.memlib.{InferReadWriteAnnotation, ReplSeqMemAnnotation}
import scala.util.DynamicVariable
import scala.reflect.ClassTag
import java.io.{File, FileWriter, Writer}

private class StroberCompiler(dir: File, io: Data)(implicit param: cde.Parameters) extends firrtl.Compiler {
  def transforms(writer: Writer): Seq[firrtl.Transform] = Seq(
    new firrtl.Chisel3ToHighFirrtl,
    new firrtl.IRToWorkingIR,
    new firrtl.ResolveAndCheck,
    new firrtl.HighFirrtlToMiddleFirrtl,
    new firrtl.passes.memlib.InferReadWrite(TransID(-1)),
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
  def apply(chirrtl: Circuit, io: Data, dir: File)(implicit p: cde.Parameters): Circuit = {
    val conf = new File(dir, s"${chirrtl.main}.conf")
    val annotations = new AnnotationMap(Seq(
      InferReadWriteAnnotation(chirrtl.main, TransID(-1)),
      ReplSeqMemAnnotation(s"-c:${chirrtl.main}:-o:$conf", TransID(-2))
    ))
    // val writer = new FileWriter(new File("debug.ir"))
    val writer = new java.io.StringWriter
    val strober = (new StroberCompiler(dir, io) compile (chirrtl, annotations, writer)).circuit
    // writer.close
    // firrtl.Parser.parse(writer.toString)
    val verilog = new FileWriter(new File(dir, s"${strober.main}.v"))
    val result = new VerilogCompiler(conf) compile (strober, annotations, verilog)
    verilog.close
    result.circuit
  }

  def apply[T <: chisel3.Module](w: => T, dir: File)(implicit p: cde.Parameters): Circuit = {
    dir.mkdirs
    lazy val target = w
    val chirrtl = firrtl.Parser.parse(chisel3.Driver.emit(() => target))
    apply(chirrtl, target.io, dir)
  }
}
