// See LICENSE for license details.

package midas.passes

import firrtl.CompilerUtils.getLoweringTransforms
import firrtl.passes.memlib.InferReadWrite
import logger._

// Compiler for Midas Transforms
private [midas] class MidasCompiler extends firrtl.Compiler {
  def emitter = new firrtl.LowFirrtlEmitter
  def transforms =
    getLoweringTransforms(firrtl.ChirrtlForm, firrtl.MidForm) ++
    Seq(new InferReadWrite) ++
    getLoweringTransforms(firrtl.MidForm, firrtl.LowForm)
}

// These next two compilers split LFO from the rest of the lowering
// compilers to schedule around the presence of internal & non-standard WIR
// nodes (Dshlw) present after LFO, which custom transforms can't handle
private [midas] class HostTransformCompiler extends firrtl.Compiler {
  def emitter = new firrtl.LowFirrtlEmitter
  def transforms =
    Seq(new firrtl.IRToWorkingIR,
        new firrtl.ResolveAndCheck,
        new firrtl.HighFirrtlToMiddleFirrtl) ++
    getLoweringTransforms(firrtl.MidForm, firrtl.LowForm)
}

// Custom transforms have been scheduled -> do the final lowering
private [midas] class LastStageVerilogCompiler extends firrtl.Compiler {
  def emitter = new firrtl.VerilogEmitter
  def transforms = Seq(new firrtl.LowFirrtlOptimization,
                       new firrtl.transforms.RemoveReset)
}
