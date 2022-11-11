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

class GoldenGateCompilerPhase extends Phase {

  override val prerequisites = Seq(
    Dependency(midas.stage.Checks),
    Dependency(midas.stage.AddDerivedAnnotations),
    Dependency[CreateParametersInstancePhase])

  def transform(annotations: AnnotationSeq): AnnotationSeq = {
    val allCircuits = annotations.collect({ case FirrtlCircuitAnnotation(circuit) => circuit })
    require(allCircuits.size == 1, "Golden Gate can only process a single Firrtl Circuit at a time.")

    implicit val p = annotations.collectFirst({ case ConfigParametersAnnotation(p)  => p }).get

    val midasAnnos = Seq(
      firrtl.passes.memlib.InferReadWriteAnnotation,
      firrtl.passes.memlib.DefaultReadFirstAnnotation,
      firrtl.passes.memlib.PassthroughSimpleSyncReadMemsAnnotation)

    val state = CircuitState(allCircuits.head, firrtl.ChirrtlForm, annotations ++ midasAnnos)

    // Lower the target design and run additional target transformations before Golden Gate xforms
    val targetLoweringCompiler = new Compiler(
      Seq(
        Dependency[midas.passes.RunConvertAssertsEarly],
        Dependency(firrtl.transforms.formal.ConvertAsserts),
        Dependency[firrtl.passes.memlib.InferReadWrite],
        Dependency[firrtl.transforms.SimplifyMems],
      ) ++
      Forms.LowForm ++
      p(TargetTransforms))
    logger.info("Pre-GG Target Transformation Ordering\n")
    logger.info(targetLoweringCompiler.prettyPrint("  "))
    val loweredTarget = targetLoweringCompiler.execute(state)

    // Run Golden Gate transformations, introducing host-decoupling and generating additional simulator RTL
    val simulator = new Compiler(
      Forms.LowForm ++ Seq(Dependency[InferReadWrite], Dependency[MidasTransforms]),
      Forms.LowForm).execute(loweredTarget)

    // Note: the final lowering to Verilog is broken up into multiple firrtl.Compiler steps
    // to avoid breakages that can emerge from "legal" but unsound pass orderings
    // due to understpecified pass constraints + invalidations
    // Shunt ILA passes to a seperate compiler to avoid invalidating downstream steps
    val loweringCompiler = new Compiler(
      targets = Seq(
          Dependency(midas.passes.AutoILATransform),
          Dependency(midas.passes.HostClockWiring)
        ) ++
        Forms.LowForm ++
        p(HostTransforms),
      currentState = Forms.LowForm)
    logger.info("Post-GG Host Transformation Ordering\n")
    logger.info(loweringCompiler.prettyPrint("  "))
    val loweredSimulator = loweringCompiler.execute(simulator)

    // Ensure FPGA backend passes run after all user-provided + ILA transforms, but before final emission
    val fpgaBackendCompiler = new Compiler(
      targets = Seq(
          Dependency[firrtl.passes.memlib.SeparateWriteClocks],
          Dependency[firrtl.passes.memlib.SetDefaultReadUnderWrite],
          Dependency[firrtl.transforms.SimplifyMems]
        ) ++
        Forms.LowForm,
      currentState = Forms.LowForm)
    logger.info("FPGA Backend Transformation Ordering\n")
    logger.info(fpgaBackendCompiler.prettyPrint("  "))
    val postLoweredSimulator = fpgaBackendCompiler.execute(simulator)

    // Workaround under-constrained transform dependencies by forcing the
    // emitter to run last in a separate compiler.
    val emitter = new Compiler(
        Seq(Dependency(midas.passes.WriteXDCFile), Dependency[firrtl.SystemVerilogEmitter]),
        Forms.LowForm)
    logger.info("Final Emission Transformation Ordering\n")
    logger.info(emitter.prettyPrint("  "))

    emitter
      .execute(postLoweredSimulator)
      .annotations
  }
}
