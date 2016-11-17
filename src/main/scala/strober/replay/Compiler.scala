package strober
package replay

import firrtl.ir.Circuit
import firrtl.Annotations.{AnnotationMap, TransID}
import firrtl.passes.memlib.{InferReadWriteAnnotation, ReplSeqMemAnnotation}
import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet}
import scala.util.DynamicVariable
import scala.reflect.ClassTag
import java.io.{File, FileWriter}

private class Compiler(conf: File) extends firrtl.Compiler {
  def transforms(writer: java.io.Writer): Seq[firrtl.Transform] = Seq(
    new firrtl.Chisel3ToHighFirrtl,
    new firrtl.IRToWorkingIR,
    new firrtl.ResolveAndCheck,
    new firrtl.HighFirrtlToMiddleFirrtl,
    new firrtl.passes.memlib.InferReadWrite(TransID(-1)),
    new firrtl.passes.memlib.ReplSeqMem(TransID(-2)),
    new firrtl.MiddleFirrtlToLowFirrtl,
    new firrtl.EmitVerilogFromLowFirrtl(writer),
    new midas.passes.EmitMemFPGAVerilog(writer, conf)
  )
}

object Compiler {
  def apply(chirrtl: Circuit, io: chisel3.Data, dir: File): Circuit = {
    dir.mkdirs
    val conf = new File(dir, s"${chirrtl.main}.conf")
    val annotations = new AnnotationMap(Seq(
      InferReadWriteAnnotation(chirrtl.main, TransID(-1)),
      ReplSeqMemAnnotation(s"-c:${chirrtl.main}:-o:$conf", TransID(-2))))
    val verilog = new FileWriter(new File(dir, s"${chirrtl.main}.v"))
    val result = new Compiler(conf) compile (chirrtl, annotations, verilog)
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
