package midas
package passes

import firrtl._
import midas.passes.partition._

class CheckCombLogicTransforms extends Transform {
  def inputForm  = LowForm
  def outputForm = LowForm

  def execute(state: CircuitState) = {
    println("Starting CombLogic Transforms")
    val xforms = Seq(
      new ResolveAndCheck,
      HoistStopAndPrintfEnables,
      firrtl.passes.RemoveValidIf,
      new firrtl.transforms.ConstantPropagation,
      firrtl.passes.SplitExpressions,
      firrtl.passes.ResolveKinds,
      firrtl.passes.CommonSubexpressionElimination,
      new firrtl.transforms.DeadCodeElimination,
      new firrtl.transforms.InferResets,
      firrtl.passes.CheckTypes,
      new HighFirrtlToMiddleFirrtl,
      new MiddleFirrtlToLowFirrtl,
      new LowerStatePass,
      new EmitFirrtl("pre-processed.fir"),
      new CheckCombLogic,
      new fame.EmitFAMEAnnotations("post-check-comb.anno.json"),
      new ResolveAndCheck,
    )
    (xforms.foldLeft(state))((in, xform) => xform.runTransform(in)).copy(form = outputForm)
  }
}
