package strober
package replay

import firrtl.ir.Circuit
import firrtl.passes.memlib._
import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet}
import scala.util.DynamicVariable
import scala.reflect.ClassTag
import java.io.{File, FileWriter}

private class Compiler(conf: File) extends firrtl.VerilogCompiler {
  override def emitter = new midas.passes.MidasVerilogEmitter(conf)
}

object Compiler {
  def apply(chirrtl: Circuit, io: chisel3.Data, dir: File): Circuit = {
    dir.mkdirs
    val conf = new File(dir, s"${chirrtl.main}.conf")
    val annotations = new firrtl.AnnotationMap(Seq(
      InferReadWriteAnnotation(chirrtl.main),
      ReplSeqMemAnnotation(s"-c:${chirrtl.main}:-o:$conf")))
    val verilog = new FileWriter(new File(dir, s"${chirrtl.main}.v"))
    val result = new Compiler(conf) compile (
      firrtl.CircuitState(chirrtl, firrtl.ChirrtlForm, Some(annotations)),
      verilog, Seq(new InferReadWrite,
                   new ReplSeqMem))
    genVerilogFragment(chirrtl.main, io, new FileWriter(new File(dir, s"${chirrtl.main}.vfrag")))
    verilog.close
    result.circuit
  }

  def apply[T <: chisel3.Module](w: => T, dir: File): Circuit = {
    lazy val dut = w
    val chirrtl = firrtl.Parser.parse(chisel3.Driver.emit(() => dut))
    apply(chirrtl, dut.io, dir)
  }
}
