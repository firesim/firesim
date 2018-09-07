// See LICENSE for license details.

package midas

import chisel3.{Data, Bundle, Record, Clock, Bool}
import chisel3.internal.firrtl.Port
import firrtl.ir.Circuit
import firrtl.{Transform, CircuitState}
import firrtl.annotations.Annotation
import firrtl.CompilerUtils.getLoweringTransforms
import firrtl.passes.memlib._
import barstools.macros._
import freechips.rocketchip.config.{Parameters, Field}
import java.io.{File, FileWriter, Writer}

// Directory into which output files are dumped. Set by dir argument
case object OutputDir extends Field[File]

// Compiler for Midas Transforms
private class MidasCompiler(dir: File, io: Seq[Data])(implicit param: Parameters) 
    extends firrtl.Compiler {
  def emitter = new firrtl.LowFirrtlEmitter
  def transforms =
    getLoweringTransforms(firrtl.ChirrtlForm, firrtl.MidForm) ++
    Seq(new InferReadWrite,
        new ReplSeqMem) ++
    getLoweringTransforms(firrtl.MidForm, firrtl.LowForm) ++
    Seq(new passes.MidasTransforms(dir, io))
}

// Compilers to emit proper verilog
private class VerilogCompiler extends firrtl.Compiler {
  def emitter = new firrtl.VerilogEmitter
  def transforms =
    Seq(new firrtl.IRToWorkingIR,
        new firrtl.ResolveAndCheck,
        new firrtl.HighFirrtlToMiddleFirrtl) ++
    getLoweringTransforms(firrtl.MidForm, firrtl.LowForm) ++
    Seq(new firrtl.LowFirrtlOptimization)
}

object MidasCompiler {
  def apply(
      chirrtl: Circuit,
      targetAnnos: Seq[Annotation],
      io: Seq[Data],
      dir: File,
      lib: Option[File],
      customTransforms: Seq[Transform])
     (implicit p: Parameters): CircuitState = {
    val conf = new File(dir, s"${chirrtl.main}.conf")
    val json = new File(dir, s"${chirrtl.main}.macros.json")
    val midasAnnos = Seq(
      InferReadWriteAnnotation,
      ReplSeqMemAnnotation("", conf.getPath),
      passes.MidasAnnotation(chirrtl.main, conf, json, lib),
      MacroCompilerAnnotation(json.toString, lib map (_.toString), CostMetric.default, MacroCompilerAnnotation.Synflops, useCompiler = false))
    val compiler = new MidasCompiler(dir, io)(p alterPartial { case OutputDir => dir })
    val midas = compiler.compile(firrtl.CircuitState(
      chirrtl, firrtl.ChirrtlForm, targetAnnos ++ midasAnnos),
      customTransforms)

    val result = (new VerilogCompiler).compileAndEmit(midas)
    val verilog = new FileWriter(new File(dir, s"FPGATop.v"))
    verilog.write(result.getEmittedCircuit.value)
    verilog.close
    result
  }

  // Unlike above, elaborates the target locally, before constructing the target IO Record.
  def apply[T <: chisel3.core.UserModule](
      w: => T,
      dir: File,
      libFile: Option[File] = None,
      customTransforms: Seq[Transform] = Nil)
     (implicit p: Parameters): CircuitState = {
    dir.mkdirs
    lazy val target = w
    val circuit = chisel3.Driver.elaborate(() => target)
    val chirrtl = firrtl.Parser.parse(chisel3.Driver.emit(circuit))
    val io = target.getPorts map (_.id)
    apply(chirrtl, circuit.annotations.map(_.toFirrtl), io, dir, libFile, customTransforms)
  }
}
