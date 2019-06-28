// See LICENSE for license details.

package midas

import passes.Utils.writeEmittedCircuit

import chisel3.{Data, Bundle, Record, Clock, Bool}
import chisel3.internal.firrtl.Port
import firrtl.ir.Circuit
import firrtl.{Transform, CircuitState}
import firrtl.annotations.Annotation
import firrtl.CompilerUtils.getLoweringTransforms
import firrtl.passes.memlib._
import freechips.rocketchip.config.{Parameters, Field}
import java.io.{File, FileWriter, Writer}

// Directory into which output files are dumped. Set by dir argument
case object OutputDir extends Field[File]

// Compiler for Midas Transforms
private class MidasCompiler extends firrtl.Compiler {
  def emitter = new firrtl.LowFirrtlEmitter
  def transforms =
    getLoweringTransforms(firrtl.ChirrtlForm, firrtl.MidForm) ++
    Seq(new InferReadWrite) ++
    getLoweringTransforms(firrtl.MidForm, firrtl.LowForm)
}

// These next two compilers split LFO from the rest of the lowering
// compilers to schedule around the presence of internal & non-standard WIR
// nodes (Dshlw) present after LFO, which custom transforms can't handle
private class HostTransformCompiler extends firrtl.Compiler {
  def emitter = new firrtl.LowFirrtlEmitter
  def transforms =
    Seq(new firrtl.IRToWorkingIR,
        new firrtl.ResolveAndCheck,
        new firrtl.HighFirrtlToMiddleFirrtl) ++
    getLoweringTransforms(firrtl.MidForm, firrtl.LowForm)
}

// Custom transforms have been scheduled -> do the final lowering
private class LastStageVerilogCompiler extends firrtl.Compiler {
  def emitter = new firrtl.VerilogEmitter
  def transforms = Seq(new firrtl.LowFirrtlOptimization)
}

object MidasCompiler {
  def apply(
      chirrtl: Circuit,
      targetAnnos: Seq[Annotation],
      io: Seq[(String, Data)],
      dir: File,
      targetTransforms: Seq[Transform], // Run pre-MIDAS transforms, on the target RTL
      hostTransforms: Seq[Transform]    // Run post-MIDAS transformations
    )
     (implicit p: Parameters): CircuitState = {
    val midasAnnos = Seq(InferReadWriteAnnotation)
    val midasTransforms = new passes.MidasTransforms(io)(p alterPartial { case OutputDir => dir })
    val compiler = new MidasCompiler
    val midas = compiler.compile(firrtl.CircuitState(
      chirrtl, firrtl.ChirrtlForm, targetAnnos ++ midasAnnos),
      targetTransforms :+ midasTransforms)

    val postHostTransforms = new HostTransformCompiler().compile(midas, hostTransforms)
    val result = new LastStageVerilogCompiler().compileAndEmit(postHostTransforms)

    writeEmittedCircuit(result, new File(dir, s"FPGATop.v"))
    result
  }

  // Unlike above, elaborates the target locally, before constructing the target IO Record.
  def apply[T <: chisel3.core.UserModule](
      w: => T,
      dir: File,
      targetTransforms: Seq[Transform] = Seq.empty,
      hostTransforms: Seq[Transform] = Seq.empty
    )
     (implicit p: Parameters): CircuitState = {
    dir.mkdirs
    lazy val target = w
    val circuit = chisel3.Driver.elaborate(() => target)
    val chirrtl = firrtl.Parser.parse(chisel3.Driver.emit(circuit))
    val io = target.getPorts map (p => p.id.instanceName -> p.id)
    apply(chirrtl, circuit.annotations.map(_.toFirrtl), io, dir, targetTransforms, hostTransforms)
  }
}
