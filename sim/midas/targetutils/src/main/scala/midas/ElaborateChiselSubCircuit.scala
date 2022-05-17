// See LICENSE for license details.

package midas.targetutils

import firrtl.{AnnotationSeq, Transform}
import firrtl.ir.Circuit
import firrtl.options.{Dependency, PhaseManager}
import firrtl.stage.{FirrtlCircuitAnnotation, RunFirrtlTransformAnnotation}
import chisel3.{RawModule}
import chisel3.stage.{ChiselGeneratorAnnotation, ChiselCircuitAnnotation}
import chisel3.experimental.RunFirrtlTransform

object ElaborateChiselSubCircuit {
/**
  * A lighter-weight mechanism for elaborating a circuit destined to be linked
  * into a larger one.  Returns the CHIRRTL-form circuit, and an annotationSeq consisting solely
  * of those elaborated by the generator itself.
  *
  * @param gen The module to be elaborated
  */
  def apply(gen: => RawModule): (Circuit, AnnotationSeq) = {
    val phase = new PhaseManager(Seq(
      Dependency[chisel3.stage.phases.Elaborate],
      Dependency[chisel3.stage.phases.MaybeAspectPhase],
      Dependency[chisel3.stage.phases.Convert])
    )

    val allAnnos = phase.transform(Seq(ChiselGeneratorAnnotation(() => gen)))
    val circuit = allAnnos.collectFirst { case FirrtlCircuitAnnotation(a) => a }.get
    val elaboratedChiselCircuit = allAnnos.collectFirst { case ChiselCircuitAnnotation(a) => a }.get

    // Note, this duplicates the work of the convert phase as this
    // seemed like the easiest way to filter out all other annotations (and is
    // consistent with what MIDAS/Golden Gate has done historically).
    val elaboratedAnnos = elaboratedChiselCircuit.annotations.map(_.toFirrtl)
    val runTransformAnnos = elaboratedChiselCircuit.annotations
      .collect {
        case anno: RunFirrtlTransform => anno.transformClass
      }
      .distinct
      .map { c: Class[_ <: Transform] => RunFirrtlTransformAnnotation(c.newInstance()) }

    (circuit, elaboratedAnnos ++ runTransformAnnos)
  }
}
