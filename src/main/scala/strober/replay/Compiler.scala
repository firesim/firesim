package strober
package replay

import firrtl._
import firrtl.ir.Circuit
import firrtl.passes.memlib._
import firrtl.CompilerUtils.getLoweringTransforms
import barstools.macros._
import java.io.{File, FileWriter}

private class Compiler(conf: File, json: File, lib: File, macros: File, paths: File) extends firrtl.Compiler {
  def transforms =
    getLoweringTransforms(ChirrtlForm, MidForm) ++
    Seq(new InferReadWrite, new ReplSeqMem) ++
    getLoweringTransforms(MidForm, LowForm) ++
    Seq(new midas.passes.ConfToJSON(conf, json),
        new MacroCompilerTransform) ++
    getLoweringTransforms(HighForm, LowForm) ++
    Seq(new LowFirrtlOptimization)
  def emitter = new StroberVerilogEmitter(lib, macros, paths)
}

object Compiler {
  def apply(chirrtl: Circuit, io: chisel3.Data, dir: File, lib: Option[File]): Circuit = {
    dir.mkdirs
    val confFile = new File(dir, s"${chirrtl.main}.conf")
    val jsonFile = new File(dir, s"${chirrtl.main}.macros.json")
    val macroFile = new File(dir, s"${chirrtl.main}.macros.v")
    val pathFile = new File(dir, s"${chirrtl.main}.macros.path")
    val annotations = new AnnotationMap(Seq(
      InferReadWriteAnnotation(chirrtl.main),
      ReplSeqMemAnnotation(s"-c:${chirrtl.main}:-o:$confFile"),
      MacroCompilerAnnotation(chirrtl.main, MacroCompilerAnnotation.Params(
        jsonFile.toString, lib map (_.toString), CostMetric.default, MacroCompilerAnnotation.Default))))
    val verilog = new FileWriter(new File(dir, s"${chirrtl.main}.v"))
    val result = new Compiler(confFile, jsonFile, lib getOrElse jsonFile, macroFile, pathFile) compile (
      CircuitState(chirrtl, ChirrtlForm, Some(annotations)), verilog)
    genVerilogFragment(chirrtl.main, io, new FileWriter(new File(dir, s"${chirrtl.main}.vfrag")))
    verilog.close
    result.circuit
  }

  def apply[T <: chisel3.Module](w: => T, dir: File, lib: Option[File] = None): Circuit = {
    lazy val dut = w
    val chirrtl = Parser.parse(chisel3.Driver.emit(() => dut))
    apply(chirrtl, dut.io, dir, lib)
  }
}
