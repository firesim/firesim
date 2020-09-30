// See LICENSE for license details.

package midas.stage

import midas.{TargetTransforms, HostTransforms}
import midas.passes.{MidasTransforms}
import midas.stage.phases.{CreateParametersInstancePhase, ConfigParametersAnnotation}

import firrtl.{CircuitState, AnnotationSeq}
import firrtl.annotations.{Annotation}
import firrtl.options.{Phase, Dependency}
import firrtl.passes.memlib.{InferReadWrite, InferReadWriteAnnotation}
import firrtl.stage.{Forms, FirrtlCircuitAnnotation}
import firrtl.stage.transforms.Compiler

class GoldenGateCompilerPhase extends Phase with ConfigLookup {

  override val prerequisites = Seq(Dependency[CreateParametersInstancePhase])
  override val optionalPrerequisiteOf = Seq(Dependency[firrtl.stage.phases.WriteEmitted])

  def transform(annotations: AnnotationSeq): AnnotationSeq = {
    val allCircuits = annotations.collect({ case FirrtlCircuitAnnotation(circuit) => circuit })
    require(allCircuits.size == 1, "Golden Gate can only process a single Firrtl Circuit at a time.")

    implicit val p = annotations.collectFirst({ case ConfigParametersAnnotation(p)  => p }).get

    val midasAnnos = Seq(InferReadWriteAnnotation)
    val state = CircuitState(allCircuits.head, firrtl.ChirrtlForm, annotations ++ midasAnnos)

    // Lower the target design and run additional target transformations before Golden Gate xforms
    val loweredTarget = new Compiler(Forms.LowForm ++ p(TargetTransforms)).execute(state)

    // Run Golden Gate transformations, introducing host-decoupling and generating additional imulator RTL
    val simulator = new Compiler(
      Forms.LowForm ++ Seq(Dependency[InferReadWrite], Dependency[MidasTransforms]),
      Forms.LowForm).execute(loweredTarget)

    // Lower and emit simulator RTL and run user-requested host-transforms
    new Compiler(Dependency(midas.passes.VerilogMemDelays) +: Dependency[firrtl.VerilogEmitter] +: p(HostTransforms),Forms.LowForm)
      .execute(simulator)
      .annotations
  }
}
