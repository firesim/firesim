package midas

import chisel3.Data
import firrtl.ir.Circuit
import firrtl.CompilerUtils.getLoweringTransforms
import firrtl.passes.memlib._
import java.io.{File, FileWriter, Writer}

// Compiler in Midas Passes
class InlineCompiler extends firrtl.Compiler {
  def emitter = new firrtl.MiddleFirrtlEmitter
  def transforms = getLoweringTransforms(firrtl.ChirrtlForm, firrtl.MidForm)
}

// Compiler for Midas Transforms
private class MidasCompiler(dir: File, io: Data)(implicit param: config.Parameters) extends firrtl.Compiler {
  def emitter = new firrtl.MiddleFirrtlEmitter
  def transforms = getLoweringTransforms(firrtl.ChirrtlForm, firrtl.MidForm) ++ Seq(
    new InferReadWrite,
    new ReplSeqMem,
    new passes.MidasTransforms(dir, io)
  )
}

// Compilers to emit proper verilog
private class VerilogCompiler extends firrtl.Compiler {
  def emitter = new firrtl.VerilogEmitter
  def transforms = getLoweringTransforms(firrtl.HighForm, firrtl.LowForm) :+ (
    new firrtl.LowFirrtlOptimization)
}


object MidasCompiler {
  def apply(chirrtl: Circuit, io: Data, dir: File)(implicit p: config.Parameters): Circuit = {
    val conf = new File(dir, s"${chirrtl.main}.conf")
    val annotations = new firrtl.AnnotationMap(Seq(
      InferReadWriteAnnotation(chirrtl.main),
      ReplSeqMemAnnotation(s"-c:${chirrtl.main}:-o:$conf"),
      passes.MidasAnnotation(chirrtl.main, conf)))
    // val writer = new FileWriter(new File("debug.ir"))
    val writer = new java.io.StringWriter
    val midas = new MidasCompiler(dir, io) compile (
      firrtl.CircuitState(chirrtl, firrtl.ChirrtlForm, Some(annotations)), writer)
    // writer.close
    // firrtl.Parser.parse(writer.toString)
    val verilog = new FileWriter(new File(dir, s"${midas.circuit.main}.v"))
    val result = new VerilogCompiler compile (
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
