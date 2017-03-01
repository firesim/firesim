package midas

import chisel3.{Data, Bits}
import firrtl.ir.Circuit
import firrtl.CompilerUtils.getLoweringTransforms
import scala.util.DynamicVariable
import scala.reflect.ClassTag
import java.io.{File, FileWriter, Writer}

// Compiler in Midas Passes
private class InlineCompiler extends firrtl.Compiler {
  def emitter = new firrtl.FirrtlEmitter
  def transforms = getLoweringTransforms(firrtl.ChirrtlForm, firrtl.MidForm)
}

// Compiler for Midas Transforms
private class MidasCompiler(dir: File, io: Data)(implicit param: config.Parameters) extends firrtl.Compiler {
  def emitter = new firrtl.FirrtlEmitter
  def transforms = getLoweringTransforms(firrtl.ChirrtlForm, firrtl.MidForm) ++ Seq(
    new firrtl.passes.memlib.InferReadWrite,
    new firrtl.passes.memlib.ReplSeqMem,
    new passes.MidasTransforms(dir, io)
  )
}

// Compilers to emit proper verilog
private class VerilogCompiler(conf: File) extends firrtl.Compiler {
  def emitter = new passes.MidasVerilogEmitter(conf)
  def transforms = getLoweringTransforms(firrtl.HighForm, firrtl.LowForm) :+ (
    new firrtl.LowFirrtlOptimization)
}

object MidasCompiler {
  def apply(chirrtl: Circuit, io: Data, dir: File)(implicit p: config.Parameters): Circuit = {
    val conf = new File(dir, s"${chirrtl.main}.conf")
    val annotations = new firrtl.Annotations.AnnotationMap(Seq(
      firrtl.passes.memlib.InferReadWriteAnnotation(chirrtl.main),
      firrtl.passes.memlib.ReplSeqMemAnnotation(s"-c:${chirrtl.main}:-o:$conf"),
      passes.MidasAnnotation(chirrtl.main, conf)
    ))
    // val writer = new FileWriter(new File("debug.ir"))
    val writer = new java.io.StringWriter
    val midas = new MidasCompiler(dir, io) compile (
      firrtl.CircuitState(chirrtl, firrtl.ChirrtlForm, Some(annotations)), writer)
    // writer.close
    // firrtl.Parser.parse(writer.toString)
    val verilog = new FileWriter(new File(dir, s"${midas.circuit.main}.v"))
    val result = new VerilogCompiler(conf) compile (
      firrtl.CircuitState(midas.circuit, firrtl.HighForm), verilog)
    verilog.close
    result.circuit
  }

  def apply[T <: chisel3.Module](w: => T, dir: File)(implicit p: config.Parameters): Circuit = {
    dir.mkdirs
    lazy val target = w
    val chirrtl = firrtl.Parser.parse(chisel3.Driver.emit(() => target))
    apply(chirrtl, target.io, dir)
  }
}
