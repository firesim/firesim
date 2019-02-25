// See LICENSE for license details.

package midas.targetutils

import chisel3._
import chisel3.experimental.{BaseModule, ChiselAnnotation, dontTouch}

import firrtl.{RenameMap}
import firrtl.annotations.{SingleTargetAnnotation, ComponentName} // Deprecated
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

  // TODO: Accept a printable -> need to somehow get the format string from it
}
