// See LICENSE for license details.

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
    Seq(new LowFirrtlOptimization)
  def emitter = new StroberVerilogEmitter(lib, macros, paths)
}

object Compiler {
  def apply(chirrtl: Circuit, io: Seq[chisel3.Data], dir: File, lib: Option[File]): Circuit = {
    dir.mkdirs
    val confFile = new File(dir, s"${chirrtl.main}.conf")
    val jsonFile = new File(dir, s"${chirrtl.main}.macros.json")
    val macroFile = new File(dir, s"${chirrtl.main}.macros.v")
    val pathFile = new File(dir, s"${chirrtl.main}.macros.path")
    val annotations = Seq(
      InferReadWriteAnnotation,
      ReplSeqMemAnnotation(chirrtl.main, confFile.getPath))
    val verilog = new FileWriter(new File(dir, s"${chirrtl.main}.v"))
    val result = new Compiler(confFile, jsonFile, lib getOrElse jsonFile, macroFile, pathFile) compile (
      CircuitState(chirrtl, ChirrtlForm, annotations), verilog)
    genVerilogFragment(chirrtl.main, io, new FileWriter(new File(dir, s"${chirrtl.main}.vfrag")))
    verilog.close
    result.circuit
  }

  def apply[T <: chisel3.core.UserModule](
      w: => T, dir: File, lib: Option[File] = None): Circuit = {
    lazy val dut = w
    val chirrtl = Parser.parse(chisel3.Driver.emit(() => dut))
    val io = dut.getPorts map (_.id)
    apply(chirrtl, io, dir, lib)
  }
}
