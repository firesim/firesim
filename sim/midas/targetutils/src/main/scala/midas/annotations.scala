// See LICENSE for license details.

package midas.targetutils

import chisel3._
import chisel3.experimental.{BaseModule, ChiselAnnotation}

import firrtl.{RenameMap}
import firrtl.annotations.{NoTargetAnnotation, SingleTargetAnnotation, ComponentName} // Deprecated
import firrtl.annotations.{ReferenceTarget, ModuleTarget, AnnotationException}

// This is currently consumed by a transformation that runs after MIDAS's core
// transformations In FireSim, targeting an F1 host, these are consumed by the
// AutoILA infrastucture (ILATopWiring pass) to generate an ILA that plays nice
// with AWS's vivado flow
case class FpgaDebugAnnotation(target: chisel3.Data) extends ChiselAnnotation {
  def toFirrtl = FirrtlFpgaDebugAnnotation(target.toNamed)
}

case class FirrtlFpgaDebugAnnotation(target: ComponentName) extends
    SingleTargetAnnotation[ComponentName] {
  def duplicate(n: ComponentName) = this.copy(target = n)
}

object FpgaDebug {
  def apply(targets: chisel3.Data*): Unit = {
    targets.map({ t => chisel3.experimental.annotate(FpgaDebugAnnotation(t)) })
    targets.map(dontTouch(_))
  }
}

private[midas] class ReferenceTargetRenamer(renames: RenameMap) {
  // TODO: determine order for multiple renames, or just check of == 1 rename?
  def exactRename(rt: ReferenceTarget): ReferenceTarget = {
    val renameMatches = renames.get(rt).getOrElse(Seq(rt)).collect({ case rt: ReferenceTarget => rt })
    assert(renameMatches.length == 1)
    renameMatches.head
  }
  def apply(rt: ReferenceTarget): Seq[ReferenceTarget] = {
    renames.get(rt).getOrElse(Seq(rt)).collect({ case rt: ReferenceTarget => rt })
  }
}

private [midas] case class SynthPrintfAnnotation(
    args: Seq[Seq[ReferenceTarget]], // These aren't currently used; here for future proofing
    mod: ModuleTarget,
    format: String,
    name: Option[String]) extends firrtl.annotations.Annotation {

  def update(renames: RenameMap): Seq[firrtl.annotations.Annotation] = {
    val renamer = new ReferenceTargetRenamer(renames)
    val renamedArgs = args.map(_.flatMap(renamer(_)))
    val renamedMod = renames.get(mod).getOrElse(Seq(mod)).collect({ case mt: ModuleTarget => mt })
    assert(renamedMod.size == 1) // To implement: handle module duplication or deletion
    Seq(this.copy(args = renamedArgs, mod = renamedMod.head ))
  }
}

// HACK: We're going to reuse the format to find the printf, from which we can grab the printf's enable
private[midas] case class ChiselSynthPrintfAnnotation(
    format: String,
    args: Seq[Bits],
    mod: BaseModule,
    name: Option[String]) extends ChiselAnnotation {
  def getTargetsFromArg(arg: Bits): Seq[ReferenceTarget] = {
    // To named throughs an exception on literals right now, so dumbly catch everything
    try {
      Seq(arg.toNamed.toTarget)
    } catch {
      case AnnotationException(_) => Seq()
    }
  }

  def toFirrtl() = SynthPrintfAnnotation(args.map(getTargetsFromArg),
                                         mod.toNamed.toTarget, format, name)
}

// For now, this needs to be invoked on the arguments to printf, not on the printf itself
// Eg. printf(SynthesizePrintf("True.B or False.B: Printfs can be annotated: %b\n", false.B))
object SynthesizePrintf {
  private def generateAnnotations(format: String, args: Seq[Bits], name: Option[String]): Printable = {
    val thisModule = Module.currentModule.getOrElse(
      throw new RuntimeException("Cannot annotate a printf outside of a Module"))
    chisel3.experimental.annotate(ChiselSynthPrintfAnnotation(format, args, thisModule, name))
    Printable.pack(format, args:_*)
  }
  def apply(name: String, format: String, args: Bits*): Printable =
    generateAnnotations(format, args, Some(name))

  def apply(format: String, args: Bits*): Printable = generateAnnotations(format, args, None)

  // TODO: Accept a printable -> need to somehow get the format string from 
}

// This labels a target Mem so that it is extracted and replaced with a separate model
case class MemModelAnnotation[T <: chisel3.Data](target: chisel3.MemBase[T])
    extends chisel3.experimental.ChiselAnnotation {
  def toFirrtl = FirrtlMemModelAnnotation(target.toNamed.toTarget)
}

case class FirrtlMemModelAnnotation(target: ReferenceTarget) extends
    SingleTargetAnnotation[ReferenceTarget] {
  def duplicate(rt: ReferenceTarget) = this.copy(target = rt)
}

case class ExcludeInstanceAssertsAnnotation(target: (String, String)) extends
    firrtl.annotations.NoTargetAnnotation {
  def duplicate(n: (String, String)) = this.copy(target = n)
}
// TODO: Actually use a real target and not strings.
object ExcludeInstanceAsserts {
  def apply(target: (String, String)): ChiselAnnotation =
    new ChiselAnnotation {
      def toFirrtl = ExcludeInstanceAssertsAnnotation(target)
    }
}


//AutoCounter annotations

case class AutoCounterCoverAnnotation(target: ReferenceTarget, label: String, message: String) extends
    SingleTargetAnnotation[ReferenceTarget] {
  def duplicate(n: ReferenceTarget) = this.copy(target = n)
}

case class AutoCounterFirrtlAnnotation(target: ReferenceTarget, label: String, message: String) extends
    SingleTargetAnnotation[ReferenceTarget] {
  def duplicate(n: ReferenceTarget) = this.copy(target = n)
}

case class AutoCounterCoverModuleFirrtlAnnotation(target: ModuleTarget) extends
    SingleTargetAnnotation[ModuleTarget] {
  def duplicate(n: ModuleTarget) = this.copy(target = n)
}

import chisel3.experimental.ChiselAnnotation
case class AutoCounterCoverModuleAnnotation(target: String) extends ChiselAnnotation {
  //TODO: fix the CircuitName arguemnt of ModuleTarget after chisel implements Target
  //It currently doesn't matter since the transform throws away the circuit name
  def toFirrtl =  AutoCounterCoverModuleFirrtlAnnotation(ModuleTarget("",target))
}

case class AutoCounterAnnotation(target: chisel3.Data, label: String, message: String) extends ChiselAnnotation {
  def toFirrtl =  AutoCounterFirrtlAnnotation(target.toNamed.toTarget, label, message)
}

object PerfCounter {
  def apply(target: chisel3.Data, label: String, message: String): Unit = {
    chisel3.experimental.annotate(AutoCounterAnnotation(target, label, message))
  }
}


