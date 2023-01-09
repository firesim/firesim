// See LICENSE for license details.

package midas.targetutils

import chisel3._
import chisel3.experimental.{BaseModule, ChiselAnnotation, annotate, requireIsHardware}

import firrtl.{RenameMap}
import firrtl.annotations._
import firrtl.transforms.DontTouchAllTargets

/**
  * These are consumed by [[midas.passes.AutoILATransform]] to directly
  * instantiate an ILA at the top of simulator's design hierarchy (the PlatformShim level).
  */
case class FpgaDebugAnnotation(target: chisel3.Data) extends ChiselAnnotation {
  def toFirrtl = FirrtlFpgaDebugAnnotation(target.toNamed)
}

case class FirrtlFpgaDebugAnnotation(target: ComponentName) extends
    SingleTargetAnnotation[ComponentName] with DontTouchAllTargets {
  def duplicate(n: ComponentName) = this.copy(target = n)
}

object FpgaDebug {
  def apply(targets: chisel3.Data*): Unit = {
    targets foreach { requireIsHardware(_, "Target passed to FpgaDebug:") }
    targets.map({ t => chisel3.experimental.annotate(FpgaDebugAnnotation(t)) })
  }
}

private[midas] class ReferenceTargetRenamer(renames: RenameMap) {
  // TODO: determine order for multiple renames, or just check of == 1 rename?
  def exactRename(rt: ReferenceTarget): ReferenceTarget = {
    val renameMatches = renames.get(rt).getOrElse(Seq(rt)).collect({ case rt: ReferenceTarget => rt })
    assert(renameMatches.length == 1,
      s"${rt} should be renamed exactly once. Suggested renames: ${renameMatches}")
    renameMatches.head
  }

  def apply(rt: ReferenceTarget): Seq[ReferenceTarget] = {
    renames.get(rt).getOrElse(Seq(rt)).collect({ case rt: ReferenceTarget => rt })
  }
}

private [midas] case class SynthPrintfAnnotation(
    target: ReferenceTarget
  ) extends firrtl.annotations.SingleTargetAnnotation[ReferenceTarget] {

  def duplicate(newTarget: ReferenceTarget) = this.copy(newTarget)
}

object SynthesizePrintf {
  /**
    * Annotates a chisel printf as a candidate for synthesis. The printf is only synthesized if Printf synthesis
    * is enabled in Golden Gate.
    *
    * See: https://docs.fires.im/en/stable/search.html?q=Printf+Synthesis&check_keywords=yes&area=default
    *
    * @param printf The printf statement to be synthesized.
    *
    * @return The original input, so that this annotator may be applied inline if desired.
    */
  def apply(printf: chisel3.printf.Printf): chisel3.printf.Printf = {
    chisel3.experimental.annotate(new ChiselAnnotation {
      def toFirrtl = SynthPrintfAnnotation(printf.toTarget)
    })
    printf
  }

  private def generateAnnotations(format: String, args: Seq[Bits], name: Option[String]): Printable = {
    val thisModule = Module.currentModule.getOrElse(
      throw new RuntimeException("Cannot annotate a printf outside of a Module"))

    // To preserve the behavior of the printf parameter annotator, generate a
    // secondary printf and annotate that, instead of the user's printf, which
    // will be given an empty string. This will be removed with the apply methods in 1.15.
    val printf = SynthesizePrintf(chisel3.printf(Printable.pack(format, args:_*)))
    name foreach { n => printf.suggestName(n) }
    Printable.pack("")
  }

  /**
    * Annotates* a printf by intercepting the parameters to a chisel printf, and returning
    * a printable. As a side effect, this function generates a ChiselSynthPrintfAnnotation with the
    * format string and references to each of the args.
    *
    * *Note: this isn't actually annotating the statement but instead the
    * arguments. This is a vestige from earlier versions of chisel / firrtl in
    * which print statements were unnamed, and thus not referenceable from
    * annotations.
    *
    * @param format The format string for the printf
    * @param args Hardware references to populate the format string.
    */

  @deprecated("This method will be removed. Annotate the printf statement directly", "FireSim 1.14")
  def apply(format: String, args: Bits*): Printable = generateAnnotations(format, args, None)

  /**
    * Like the other apply method, but provides an optional name which can be
    * used by synthesized hardware / bridge. Generally, users deploy the nameless form.
    *
    * @param name A descriptive name for this printf instance.
    * @param format The format string for the printf
    * @param args Hardware references to populate the format string.
    */
  @deprecated("This method will be removed. Annotate the printf statement directly", "FireSim 1.14")
  def apply(name: String, format: String, args: Bits*): Printable =
    generateAnnotations(format, args, Some(name))
}

/**
  * A mixed-in ancestor trait for all FAME annotations, useful for type-casing.
  */
trait FAMEAnnotation {
  this: Annotation =>
}

/**
  * This labels an instance so that it is extracted as a separate FAME model.
  */
case class FAMEModelAnnotation(target: BaseModule) extends chisel3.experimental.ChiselAnnotation {
  def toFirrtl: FirrtlFAMEModelAnnotation = {
    val parent = ModuleTarget(target.toNamed.circuit.name, target.parentModName)
    FirrtlFAMEModelAnnotation(parent.instOf(target.instanceName, target.name))
  }
}

case class FirrtlFAMEModelAnnotation(
  target: InstanceTarget) extends SingleTargetAnnotation[InstanceTarget] with FAMEAnnotation {
  def targets = Seq(target)
  def duplicate(n: InstanceTarget) = this.copy(n)
}

/**
  * This specifies that the module should be automatically multi-threaded (Chisel annotator).
  */
case class EnableModelMultiThreadingAnnotation(target: BaseModule) extends chisel3.experimental.ChiselAnnotation {
  def toFirrtl: FirrtlEnableModelMultiThreadingAnnotation = {
    val parent = ModuleTarget(target.toNamed.circuit.name, target.parentModName)
    FirrtlEnableModelMultiThreadingAnnotation(parent.instOf(target.instanceName, target.name))
  }
}

/**
  * This specifies that the module should be automatically multi-threaded (FIRRTL annotation).
  */
case class FirrtlEnableModelMultiThreadingAnnotation(
  target: InstanceTarget) extends SingleTargetAnnotation[InstanceTarget] with FAMEAnnotation {
  def targets = Seq(target)
  def duplicate(n: InstanceTarget) = this.copy(n)
}

/**
  * This labels a target Mem so that it is extracted and replaced with a separate model.
  */
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

sealed trait PerfCounterOpType

object PerfCounterOps {
  /**
    *  Takes the annotated UInt and adds it to an accumulation register generated in the bridge
    */
  case object Accumulate extends PerfCounterOpType
  /** Takes the annotated UInt and exposes it directly to the driver
    * NB: Fields longer than 64b are not supported, and must be divided into
    * smaller segments that are sepearate annotated
    */
  case object Identity extends PerfCounterOpType
}

/**
  * AutoCounter annotations. Do not emit the FIRRTL annotations unless you are
  * writing a target transformation, use the Chisel-side [[PerfCounter]] object
  * instead.
  *
  */
case class AutoCounterFirrtlAnnotation(
  target: ReferenceTarget,
  clock: ReferenceTarget,
  reset: ReferenceTarget,
  label: String,
  description: String,
  opType: PerfCounterOpType = PerfCounterOps.Accumulate,
  coverGenerated: Boolean = false)
    extends firrtl.annotations.Annotation with DontTouchAllTargets with HasSerializationHints {
  def update(renames: RenameMap): Seq[firrtl.annotations.Annotation] = {
    val renamer = new ReferenceTargetRenamer(renames)
    val renamedTarget = renamer.exactRename(target)
    val renamedClock  = renamer.exactRename(clock)
    val renamedReset  = renamer.exactRename(reset)
    Seq(this.copy(target = renamedTarget, clock = renamedClock, reset = renamedReset))
  }
  // The AutoCounter tranform will reject this annotation if it's not enclosed
  def shouldBeIncluded(modList: Seq[String]): Boolean = !coverGenerated || modList.contains(target.module)
  def enclosingModule(): String = target.module
  def enclosingModuleTarget(): ModuleTarget = ModuleTarget(target.circuit, enclosingModule())
  def typeHints: Seq[Class[_]] = Seq(opType.getClass)
}

case class AutoCounterCoverModuleFirrtlAnnotation(target: ModuleTarget) extends
    SingleTargetAnnotation[ModuleTarget] with FAMEAnnotation {
  def duplicate(n: ModuleTarget) = this.copy(target = n)
}

case class AutoCounterCoverModuleAnnotation(target: ModuleTarget) extends ChiselAnnotation {
  def toFirrtl =  AutoCounterCoverModuleFirrtlAnnotation(target)
}


object PerfCounter {
  private def emitAnnotation(
      target: chisel3.UInt,
      clock: chisel3.Clock,
      reset: Reset,
      label: String,
      description: String,
      opType: PerfCounterOpType): Unit = {
    requireIsHardware(target, "Target passed to PerfCounter:")
    requireIsHardware(clock,  "Clock passed to PerfCounter:")
    requireIsHardware(reset,  "Reset passed to PerfCounter:")
    annotate(new ChiselAnnotation {
      def toFirrtl = AutoCounterFirrtlAnnotation(
        target.toTarget,
        clock.toTarget,
        reset.toTarget,
        label,
        description,
        opType)
    })
  }

  /**
    * Labels a signal as an event for which an host-side counter (an
    * "AutoCounter") should be generated).  Events can be multi-bit to encode
    * multiple occurances in a cycle (e.g., the number of instructions retired
    * in a superscalar processor). NB: Golden Gate will not generate the
    * coutner unless AutoCounter is enabled in your the platform config. See
    * the docs.fires.im for end-to-end usage information.
    *
    * @param target The number of occurances of the event (in the current cycle) 
    *
    * @param clock The clock to which this event is sychronized.
    *
    * @param reset If the event is asserted while under the provide reset, it
    * is not counted. TODO: This should be made optional.
    *
    * @param label A verilog-friendly identifier for the event signal
    *
    * @param description A human-friendly description of the event.
    *
    * @param opType Defines how the bridge should be aggregated into a performance counter.
    *
    */
  def apply(
      target: chisel3.UInt,
      clock: chisel3.Clock,
      reset: Reset,
      label: String,
      description: String,
      opType: PerfCounterOpType = PerfCounterOps.Accumulate): Unit =
    emitAnnotation(target, clock, reset, label, description, opType)

  /**
    * A simplified variation of the full apply method above that uses the
    * implicit clock and reset.
    */
  def apply(target: chisel3.UInt, label: String, description: String): Unit =
    emitAnnotation(target, Module.clock, Module.reset, label, description, PerfCounterOps.Accumulate)

  /**
    * Passes the annotated UInt through to the driver without accumulation.
    * Use cases:
    *   - Custom accumulation / counting logic not supported by the driver
    *   - Providing runtime metadata along side standard accumulation registers
    *
    * Note: Under reset, the passthrough value is set to 0. This keeps event
    * handling uniform in the transform.
    *
    */
  def identity(target: chisel3.UInt, label: String, description: String): Unit = {
    require(target.getWidth <= 64,
      s"""|PerfCounter.identity can only accept fields <= 64b wide. Provided target for label:
          |  $label
          |was ${target.getWidth}b.""".stripMargin)
    emitAnnotation(target, Module.clock, Module.reset, label, description, opType = PerfCounterOps.Identity)
  }
}

// Need serialization utils to be upstreamed to FIRRTL before i can use these.
//sealed trait TriggerSourceType
//case object Credit extends TriggerSourceType
//case object Debit extends TriggerSourceType

case class TriggerSourceAnnotation(
    target: ReferenceTarget,
    clock: ReferenceTarget,
    reset: Option[ReferenceTarget],
    sourceType: Boolean) extends Annotation with FAMEAnnotation with DontTouchAllTargets {
  def update(renames: RenameMap): Seq[firrtl.annotations.Annotation] = {
    val renamer = new ReferenceTargetRenamer(renames)
    val renamedTarget = renamer.exactRename(target)
    val renamedClock  = renamer.exactRename(clock)
    val renamedReset  = reset map renamer.exactRename
    Seq(this.copy(target = renamedTarget, clock = renamedClock, reset = renamedReset))
  }
  def enclosingModuleTarget(): ModuleTarget = ModuleTarget(target.circuit, target.module)
  def enclosingModule(): String = target.module
}


case class TriggerSinkAnnotation(
    target: ReferenceTarget,
    clock: ReferenceTarget) extends Annotation with FAMEAnnotation with DontTouchAllTargets {
  def update(renames: RenameMap): Seq[firrtl.annotations.Annotation] = {
    val renamer = new ReferenceTargetRenamer(renames)
    val renamedTarget = renamer.exactRename(target)
    val renamedClock  = renamer.exactRename(clock)
    Seq(this.copy(target = renamedTarget, clock = renamedClock))
  }
  def enclosingModuleTarget(): ModuleTarget = ModuleTarget(target.circuit, target.module)
}

object TriggerSource {
  private def annotateTrigger(tpe: Boolean)(target: Bool, reset: Option[Bool]): Unit = {
    // Hack: Create dummy nodes until chisel-side instance annotations have been improved
    val clock = WireDefault(Module.clock)
    reset.map(dontTouch.apply)
    requireIsHardware(target, "Target passed to TriggerSource:")
    reset.foreach { requireIsHardware(_,  "Reset passed to TriggerSource:") }
    annotate(new ChiselAnnotation {
      def toFirrtl = TriggerSourceAnnotation(target.toNamed.toTarget, clock.toNamed.toTarget, reset.map(_.toTarget), tpe)
    })
  }
  def annotateCredit = annotateTrigger(true) _
  def annotateDebit = annotateTrigger(false) _

  /**
    * Methods to annotate a Boolean as a trigger credit or debit. Credits and
    * debits issued while the module's implicit reset is asserted are not
    * counted.
    */
  def credit(credit: Bool): Unit = annotateCredit(credit, Some(Module.reset.asBool))
  def debit(debit: Bool): Unit = annotateDebit(debit, Some(Module.reset.asBool))
  def apply(creditSig: Bool, debitSig: Bool): Unit = {
    credit(creditSig)
    debit(debitSig)
  }

  /**
    * Variations of the above methods that count credits and debits provided
    * while the implicit reset is asserted.
    */
  def creditEvenUnderReset(credit: Bool): Unit = annotateCredit(credit, None)
  def debitEvenUnderReset(debit: Bool): Unit = annotateDebit(debit, None)
  def evenUnderReset(creditSig: Bool, debitSig: Bool): Unit = {
    creditEvenUnderReset(creditSig)
    debitEvenUnderReset(debitSig)
  }

  /**
    * Level sensitive trigger sources. Implemented using [[credit]] and [[debit]].
    * Note: This generated hardware in your target design.
    *
    * @param src Enables the trigger when asserted. If no other credits have
    * been issued since (e.g., a second level-sensitive enable was asserted),
    * the trigger is disabled when src is desasserted.
    */
  def levelSensitiveEnable(src: Bool): Unit = {
    val srcLast = RegNext(src)
    credit(src && !srcLast)
    debit(!src && srcLast)
  }

}

object TriggerSink {
  /**
    * Marks a bool as receiving the global trigger signal.
    *
    * @param target A Bool node that will be driven with the trigger
    *
    * @param noSourceDefault The value that the trigger signal should take on
    * if no trigger soruces are found in the target. This is a temporary parameter required
    * while this apply method generates a wire. Otherwise this can be punted to the target's RTL.
    */
  def apply(target: Bool, noSourceDefault: =>Bool = true.B): Unit = {
    // Hack: Create dummy nodes until chisel-side instance annotations have been improved
    val targetWire = WireDefault(noSourceDefault)
    val clock = Module.clock
    target := targetWire
    // Both the provided node and the generated one need to be dontTouched to stop
    // constProp from optimizing the down stream logic(?)
    dontTouch(target)
    annotate(new ChiselAnnotation {
      def toFirrtl = TriggerSinkAnnotation(targetWire.toTarget, clock.toTarget)
    })
  }

  /**
    * Syntatic sugar for a when context that is predicated by a trigger sink.
    * Example usage:
    * {{{
    * TriggerSink.whenEnabled {
    *   printf(<...>)
    * }
    * }}}
    *
    * @param noSourceDefault See [[TriggerSink.apply]].
    */
  def whenEnabled(noSourceDefault: =>Bool = true.B)(elaborator: => Unit): Unit = {
    val sinkEnable = Wire(Bool())
    apply(sinkEnable, noSourceDefault)
    when (sinkEnable) { elaborator }
  }
}
