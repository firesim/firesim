// See LICENSE for license details.

package midas
package passes

import midas.passes.partition._

import firrtl._

private[midas] class MidasTransforms extends Transform {
  def inputForm  = LowForm
  def outputForm = HighForm

  def execute(state: CircuitState) = {
    println("Starting MidasTransforms")

    // First convert all external annotations to internal ones
    val newAnnos      = midas.ConvertExternalToInternalAnnotations(state.annotations)
    val internalState = state.copy(annotations = newAnnos)

    // Optionally run if the GenerateMultiCycleRamModels parameter is set
    val p                        = internalState.annotations.collectFirst({ case midas.stage.phases.ConfigParametersAnnotation(p) => p }).get
    val optionalTargetTransforms =
      if (p(GenerateMultiCycleRamModels))
        Seq(new fame.LabelSRAMModels, new ResolveAndCheck, new EmitFirrtl("post-wrap-sram-models.fir"))
      else Seq()

    val partition = p(FireAxePartitionGlobalInfo).isDefined
    val extract   = p(FireAxePartitionIndex).isDefined

    val performExtractPass = if (partition && extract) {
      println("PerformExtractPass")
      Seq(
        new CheckCombPathLength,
        new WrapAndGroupModulesToPartition,
        new EmitFirrtl("post-wrap-and-group.fir"),
        new fame.EmitFAMEAnnotations("post-wrap-and-group.json"),
        new ResolveAndCheck,
        new CheckCombLogic,
        new fame.EmitFAMEAnnotations("post-check-comb.json"),
        new GenerateFireSimWrapper,
        new EmitFirrtl("post-gen-firesim-wrapper.fir"),
        new fame.EmitFAMEAnnotations("post-gen-firesim-wrapper.json"),
        new PrunedExtraModulesAndAddBridgeAnnos,
        new EmitFirrtl("post-prune-extra.fir"),
        new fame.EmitAllAnnotations("post-prune-extra.json"),
        new ResolveAndCheck,
        new ModifyTargetBoundaryForExtractPass,
        new EmitFirrtl("post-modify-boundary.fir"),
        new fame.EmitFAMEAnnotations("post-modify-boundary.json"),
        new ResolveAndCheck,
        new EmitFirrtl("post-modify-boundary-and-resolve.fir"),
      )
    } else {
      Seq()
    }

    val performRemovePass = if (partition && !extract) {
      println("PerformRemovePass")
      Seq(
        new CheckCombPathLength,
        new WrapAndGroupModulesToPartition,
        new EmitFirrtl("post-wrap-and-group.fir"),
        new fame.EmitFAMEAnnotations("post-wrap-and-group.json"),
        new ResolveAndCheck,
        new CheckCombLogic,
        new fame.EmitFAMEAnnotations("post-check-comb.json"),
        new GenerateCutBridgeInGroupedWrapper,
        new EmitFirrtl("post-gen-cutbridge.fir"),
        new fame.EmitFAMEAnnotations("post-gen-cutbridge.json"),
        new ResolveAndCheck,
        new ModifyTargetBoundaryForRemovePass,
        new EmitFirrtl("post-modify-boundary.fir"),
        new fame.EmitAllAnnotations("post-modify-boundary.json"),
        new PruneUnrelatedAnnoPass,
        new fame.EmitAllAnnotations("post-prune.json"),
        new ResolveAndCheck,
        new EmitFirrtl("post-modify-boundary-and-resolve.fir"),
      )
    } else {
      Seq()
    }

    val nocPartitionPass = if (p(FireAxeNoCPartitionPass)) {
      println("PerformNoCPass")
      Seq(
        new LowerStatePass,
        new ResolveAndCheck,
        new RemoveDirectWireConnectionPass,
        new ResolveAndCheck,
        new NoCConnectHartIdPass,
        new EmitFirrtl("post-hartid-connection.fir"),
        new ResolveAndCheck,
        new NoCPartitionRoutersPass,
        new EmitFirrtl("post-group-router.fir"),
        new fame.EmitFAMEAnnotations("post-group-router.json"),
        new ResolveAndCheck,
        new NoCReparentRouterGroupPass,
        new EmitFirrtl("post-reparent-router.fir"),
        new ResolveAndCheck,
        new NoCCollectModulesInPathAndRegroupPass,
        new EmitFirrtl("post-collect-and-reparent.fir"),
        new ResolveAndCheck,
        new DedupClockAndResetPass,
        new EmitFirrtl("post-dedup-clock-and-reset.fir"),
        new NoCConnectInterruptsPass,
        new ResolveAndCheck,
        new EmitFirrtl("post-connect-interrupts-and-resolve.fir"),
      )
    } else {
      Seq()
    }

    val fireAxePasses = nocPartitionPass ++ performRemovePass ++ performExtractPass

    // HACK : Only perfrom dedup when the current pass is not a remove module pass.
    // This is only a temporary solution.
    // Dedup here in 'AQB form' after lowering instance bulk connects
    val optionalDedup = if (partition && !extract) Seq() else Seq(new midas.passes.EnableAndRunDedupOnce)

    val xforms = Seq(
      new ResolveAndCheck,
      HoistStopAndPrintfEnables,
      firrtl.passes.RemoveValidIf,
      new firrtl.transforms.ConstantPropagation,
      firrtl.passes.SplitExpressions,
      new EmitFirrtl("post-split-expressions.fir"),
      new fame.EmitFAMEAnnotations("post-split-expressions.json"),
      // SplitExpressions invalidates ResolveKinds which can lead to missed CSE opportunities since
      // identical expressions may have different Kinds
      firrtl.passes.ResolveKinds,
      firrtl.passes.CommonSubexpressionElimination,
      new firrtl.transforms.DeadCodeElimination,
      new firrtl.transforms.InferResets,
      firrtl.passes.CheckTypes,
      new HighFirrtlToMiddleFirrtl,
      new MiddleFirrtlToLowFirrtl,
      new EmitFirrtl("pre-partition.fir"),
      new fame.EmitFAMEAnnotations("pre-partition.json"),
      new fame.EmitAllAnnotations("pre-partition-all.json"),
    ) ++
      fireAxePasses ++
      Seq(
        new EmitFirrtl("post-partition.fir"),
        new fame.EmitFAMEAnnotations("post-partition.json"),
        new fame.EmitAllAnnotations("post-partition-all.json"),
        PlusArgsWiringTransform,
        new EmitFirrtl("post-plusargs-wiring.fir"),
        new fame.EmitFAMEAnnotations("post-plusargs-wiring.json"),
        CoerceAsyncToSyncReset,
        EnsureNoTargetIO,         // Simple checking pass
        new BridgeExtraction,     // Promote all the bridges to the top level / Add FAMEChannelConnectionAnnotation (which indicates the top level connections for FAMETop)
        new ResolveAndCheck,
        new EmitFirrtl("post-bridge-extraction.fir"),
        new fame.EmitFAMEAnnotations("post-bridge-extraction.json"),
        new HighFirrtlToMiddleFirrtl,
        new MiddleFirrtlToLowFirrtl,
        new AutoCounterTransform,
        new EmitFirrtl("post-autocounter.fir"),
        new fame.EmitFAMEAnnotations("post-autocounter.json"),
        new ResolveAndCheck,
        new AssertionSynthesis,
        new PrintSynthesis,
        new ResolveAndCheck,
        new EmitFirrtl("post-debug-synthesis.fir"),
        new fame.EmitFAMEAnnotations("post-debug-synthesis.json"),
        // All trigger sources and sinks must exist in the target RTL before this pass runs
        // As its naming suggests, it wires up all the trigger sources
        TriggerWiring,
        new EmitFirrtl("post-trigger-wiring.fir"),
        new fame.EmitFAMEAnnotations("post-trigger-wiring.json"),
        GlobalResetConditionWiring,
        // We should consider moving these lower
        ChannelClockInfoAnalysis, // Adds annotations containing info about clocks for each channel
        UpdateBridgeClockInfo,    // Determines each bridges clock domain & adds annotations about it
        fame.WrapTop,             // Wrap FireSim with FAMETop
        fame.LabelMultiThreadedInstances,
        new ResolveAndCheck,
        new EmitFirrtl("post-wrap-top.fir"),
        new fame.EmitAllAnnotations("post-wrap-top-all.json"),
      ) ++
      optionalTargetTransforms ++
      Seq(
        new EmitFirrtl("pre-extract-model.fir"),
        new fame.EmitAllAnnotations("pre-extract-model-all.json"),
        new fame.ExtractModel,
        new ResolveAndCheck,
        new EmitFirrtl("post-extract-model.fir"),
        new fame.EmitAllAnnotations("post-extract-model-all.json"),
        new HighFirrtlToMiddleFirrtl,
        new MiddleFirrtlToLowFirrtl,
      ) ++
      optionalDedup ++
      Seq(
        fame.PromotePassthroughConnections,
        new ResolveAndCheck,
        new EmitFirrtl("post-promote-passthrough.fir"),
        new fame.EmitFAMEAnnotations("post-promote-passthrough.json"),
        new fame.FAMEDefaults,
        new EmitFirrtl("post-fame-defaults.fir"),
        new fame.EmitFAMEAnnotations("post-fame-defaults.json"),
        fame.FindDefaultClocks,
        new fame.EmitFAMEAnnotations("post-find-default-clocks.json"),
        new fame.ChannelExcision,
        new fame.EmitFAMEAnnotations("post-channel-excision.json"),
        new EmitFirrtl("post-channel-excision.fir"),
        // We could delay adding FAMETransformAnnotations to all top modules to here (not used before this)
        new fame.InferModelPorts,
        new EmitFirrtl("post-infer-model-ports.fir"),
        new fame.EmitFAMEAnnotations("post-infer-model-ports.json"),
        new fame.FAMETransform,
        DefineAbstractClockGate,
        fame.AddRemainingFanoutAnnotations,
        new EmitFirrtl("post-fame-transform.fir"),
        new fame.EmitFAMEAnnotations("post-fame-transform.json"),
        new ResolveAndCheck,
        new EmitFirrtl("pre-fame5-transform.fir"),
        new fame.EmitFAMEAnnotations("pre-fame5-transform.json"),
        fame.MultiThreadFAME5Models,
        new EmitFirrtl("post-fame5-transform.fir"),
        new fame.EmitFAMEAnnotations("post-fame5-transform.json"),
        new ResolveAndCheck,
        new passes.InlineInstances,
        passes.ResolveKinds,
        new fame.EmitAndWrapRAMModels,
        new ResolveAndCheck,
        new EmitFirrtl("post-gen-sram-models.fir"),
        new fame.EmitFAMEAnnotations("post-gen-sram-models.json"),
        new SimulationMapping(internalState.circuit.main),
        xilinx.HostSpecialization,
        new ResolveAndCheck,
      )
    (xforms.foldLeft(internalState))((in, xform) => xform.runTransform(in)).copy(form = outputForm)
  }
}
