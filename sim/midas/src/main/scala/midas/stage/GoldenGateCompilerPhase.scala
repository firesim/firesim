// See LICENSE for license details.

package midas.stage

import midas.{HostTransforms, TargetTransforms}
import midas.passes.MidasTransforms
import midas.stage.phases.{ConfigParametersAnnotation, CreateParametersInstancePhase}
import firrtl.{AnnotationSeq, CircuitState}
import firrtl.options.{Dependency, Phase}
import firrtl.passes.memlib.{InferReadWrite, InferReadWriteAnnotation}
import firrtl.stage.phases.{AddCircuit, AddDefaults, AddImplicitEmitter, AddImplicitOutputFile, Checks}
import firrtl.stage.{FirrtlCircuitAnnotation, Forms}
import firrtl.stage.transforms.Compiler

class GoldenGateCompilerPhase extends Phase with ConfigLookup {

  override val prerequisites = Seq(
    Dependency[AddDefaults],
    Dependency[AddImplicitEmitter],
    Dependency[Checks],
    Dependency[AddCircuit],
    Dependency[AddImplicitOutputFile],
    Dependency[CreateParametersInstancePhase]
  )

  override val optionalPrerequisiteOf = Seq(Dependency[firrtl.stage.phases.WriteEmitted])

  def transform(annotations: AnnotationSeq): AnnotationSeq = {
    val allCircuits = annotations.collect({ case FirrtlCircuitAnnotation(circuit) => circuit })
    require(allCircuits.size == 1, "Golden Gate can only process a single Firrtl Circuit at a time.")

    implicit val p = annotations.collectFirst({ case ConfigParametersAnnotation(p)  => p }).get

    val midasAnnos = Seq(InferReadWriteAnnotation)
    val state = CircuitState(allCircuits.head, firrtl.ChirrtlForm, annotations ++ midasAnnos)

    // Lower the target design and run additional target transformations before Golden Gate xforms
    val targetLoweringCompiler = new Compiler(
      Seq(Dependency[midas.passes.DedupModules]) ++ Forms.LowForm ++ p(TargetTransforms))
    logger.info("Pre-GG Target Transformation Ordering\n")
    logger.info(targetLoweringCompiler.prettyPrint("  "))
    val loweredTarget = targetLoweringCompiler.execute(state)

    // Run Golden Gate transformations, introducing host-decoupling and generating additional imulator RTL
    val simulator = new Compiler(
      Forms.LowForm ++ Seq(Dependency[InferReadWrite], Dependency[MidasTransforms]),
      Forms.LowForm).execute(loweredTarget)

    // Lower and emit simulator RTL and run user-requested host-transforms
    val hostLoweringCompiler = new Compiler(
      Dependency[firrtl.VerilogEmitter] +:
      p(HostTransforms),Forms.LowForm)
    logger.info("Post-GG Host Transformation Ordering\n")
    logger.info(hostLoweringCompiler.prettyPrint("  "))
    hostLoweringCompiler.execute(simulator).annotations
  }
}
