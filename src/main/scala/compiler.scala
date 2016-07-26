package strober

import firrtl._
import firrtl.ir._
import firrtl.passes._
import Annotations.AnnotationMap
import scala.collection.mutable.{HashMap, HashSet}
import scala.util.DynamicVariable

private class TransformContext {
  val shims = HashSet[String]()
  val wrappers = HashSet[String]()
  val modules = HashSet[String]()
  val children = HashMap[String, Vector[String]]()
}

class StroberTransforms extends Transform with SimpleRun {
  val passSeq = Seq(
    strober.passes.Analyses,
    strober.passes.Fame1Transform
  )
  def execute(circuit: Circuit, annotationMap: AnnotationMap) =
    run(circuit, passSeq)
}

class StroberCompiler extends Compiler {
  def transforms(writer: java.io.Writer): Seq[Transform] = Seq(
    new Chisel3ToHighFirrtl,
    new IRToWorkingIR,
    new ResolveAndCheck,
    new HighFirrtlToMiddleFirrtl,
    new InferReadWrite,
    new StroberTransforms,
    new EmitFirrtl(writer)
  )
}

object StroberCompiler {
  private val contextVar = new DynamicVariable[Option[TransformContext]](None)
  private[strober] def context = contextVar.value.getOrElse (new TransformContext)

  private def moduleToFirrtl(c: chisel3.internal.firrtl.Circuit, dir: java.io.File) = {
    val firrtl = s"${dir}/${c.name}.fir"
    chisel3.Driver.dumpFirrtl(c, Some(new java.io.File(firrtl)))
    firrtl
  }

  private def transform(name: String, highFirrtl: String, dir: java.io.File) = {
    val firrtl = s"${dir}/${name}-strober.fir"
    val annotations = new AnnotationMap(Seq(new InferReadWriteAnnotation(name)))
    Driver.compile(highFirrtl, firrtl, new StroberCompiler, annotations=annotations)
    firrtl
  }

  private def firrtlToVerilog(name: String, firrtl: String, dir: java.io.File) = {
    val v = s"${dir}/${name}.v"
    Driver.compile(firrtl, v, new VerilogCompiler)
    v
  }

  private def dumpIoMap[T <: SimNetwork](c: ZynqShim[T]) {
    object MapType extends Enumeration { val IoIn, IoOut, InTr, OutTr = Value }
    val targetName = c.sim match { case sim: SimWrapper[_] => sim.target.name }
    val nameMap = (c.sim.io.inputs ++ c.sim.io.outputs).toMap
    def dump(map_t: MapType.Value, arg: (chisel3.Bits, Int)) = arg match {case (wire, id) =>
      s"${map_t.id} ${targetName}.${nameMap(wire)} ${id} ${SimUtils.getChunks(wire)(c.sim.channelWidth)}\n"} 
    val sb = new StringBuilder
    sb append (c.IN_ADDRS map (x => (x._1, x._2 - c.CTRL_NUM))  map {dump(MapType.IoIn,  _)} mkString "")
    sb append (c.OUT_ADDRS map (x => (x._1, x._2 - c.CTRL_NUM)) map {dump(MapType.IoOut, _)} mkString "")

    val filePath = s"${chisel3.Driver.targetDir}/${targetName}.map"
    val file = new java.io.FileWriter(new java.io.File(filePath))
    try {
      file write sb.result
    } finally {
      file.close
      sb.clear
    }
  }

  private def dumpHeader[T <: SimNetwork](c: ZynqShim[T]) {
    implicit val channelWidth = c.sim.channelWidth
    val targetName = c.sim match { case sim: SimWrapper[_] => sim.target.name }
    def dump(arg: (String, Int)) = s"#define ${arg._1} ${arg._2}\n"
    val consts = List(
      "RESET_ADDR"         -> c.RESET_ADDR,
      "STEP_ADDR"          -> c.STEP_ADDR,
      "DONE_ADDR"          -> c.DONE_ADDR,
      "TRACELEN_ADDR"      -> c.TRACELEN_ADDR,
      "LATENCY_ADDR"       -> c.LATENCY_ADDR,
      "MEM_AR_ADDR"        -> c.AR_ADDR,
      "MEM_AW_ADDR"        -> c.AW_ADDR,
      "MEM_W_ADDR"         -> c.W_ADDR,
      "MEM_R_ADDR"         -> c.R_ADDR,
      "CTRL_NUM"           -> c.CTRL_NUM,
      "POKE_SIZE"          -> c.ins.size,
      "PEEK_SIZE"          -> c.outs.size,
      "TRACE_MAX_LEN"      -> c.sim.traceMaxLen,
      "MEM_DATA_BITS"      -> c.arb.nastiXDataBits,
      "MEM_DATA_CHUNK"     -> SimUtils.getChunks(c.io.slave.w.bits.data)
    )
    val sb = new StringBuilder
    sb append "#ifndef __%s_H\n".format(targetName.toUpperCase)
    sb append "#define __%s_H\n".format(targetName.toUpperCase)
    consts foreach (sb append dump(_))
    sb append "#endif  // __%s_H\n".format(targetName.toUpperCase)
    val filePath = s"${chisel3.Driver.targetDir}/${targetName}-const.h"
    val file = new java.io.FileWriter(new java.io.File(filePath))
    try {
      file write sb.result
    } finally {
      file.close
      sb.clear
    }
  }

  def apply[T <: chisel3.Module](args: Array[String], w: => T) = {
    contextVar.withValue(Some(new TransformContext)) {
      chisel3.Driver.parseArgs(args)
      val circuit = chisel3.Driver.elaborate(() => w)
      val dir = new java.io.File(chisel3.Driver.targetDir) ; dir.mkdirs
      val highFirrtl = moduleToFirrtl(circuit, dir)
      val dut = (circuit.components find (_.name == circuit.name)).get.id 
      dut match {
        case w: SimWrapper[_] =>
          context.wrappers += w.name
        case w: ZynqShim[_] =>
          context.shims += w.name
          context.wrappers += w.sim.name
      }
      val firrtl = transform(circuit.name, highFirrtl, dir)
      firrtlToVerilog(circuit.name, firrtl, dir)
      dut match {
        case w: ZynqShim[_] =>
          dumpIoMap(w)
          dumpHeader(w)
        case _ =>
      }
      circuit
    }
  }
}
