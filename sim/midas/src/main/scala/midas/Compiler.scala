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
import logger._

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
  def transforms = Seq(new firrtl.LowFirrtlOptimization,
                       new firrtl.transforms.RemoveReset)
}
