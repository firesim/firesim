// See LICENSE for license details.

package midas
package passes



import firrtl._


private[midas] class MidasTransforms extends Transform {
  def inputForm = LowForm
  def outputForm = HighForm

  //Logger.setLevel(LogLevel.Info)
  def execute(state: CircuitState) = {
    // Optionally run if the GenerateMultiCycleRamModels parameter is set
    val p = state.annotations.collectFirst({ case midas.stage.phases.ConfigParametersAnnotation(p)  => p }).get
    val optionalTargetTransforms = if (p(GenerateMultiCycleRamModels)) Seq(
      new fame.LabelSRAMModels,
      new ResolveAndCheck,
      new EmitFirrtl("post-wrap-sram-models.fir"))
    else Seq()

    val xforms = Seq(
      new ResolveAndCheck,
      HoistStopAndPrintfEnables,
      firrtl.passes.RemoveValidIf,
      new firrtl.transforms.ConstantPropagation,
      firrtl.passes.SplitExpressions,
      // SplitExpressions invalidates ResolveKinds which can lead to missed CSE opportunities since
      // identical expressions may have different Kinds
      firrtl.passes.ResolveKinds,
      firrtl.passes.CommonSubexpressionElimination,
      new firrtl.transforms.DeadCodeElimination,
      new firrtl.transforms.InferResets,
      firrtl.passes.CheckTypes,
      CoerceAsyncToSyncReset,
      EnsureNoTargetIO,
      new BridgeExtraction,
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
      TriggerWiring,
      new EmitFirrtl("post-trigger-wiring.fir"),
      new fame.EmitFAMEAnnotations("post-trigger-wiring.json"),
      GlobalResetConditionWiring,
      // We should consider moving these lower
      ChannelClockInfoAnalysis,
      UpdateBridgeClockInfo,
      fame.WrapTop,
      fame.LabelMultiThreadedInstances,
      new ResolveAndCheck,
      new EmitFirrtl("post-wrap-top.fir")) ++
    optionalTargetTransforms ++
    Seq(
      new fame.ExtractModel,
      new ResolveAndCheck,
      new EmitFirrtl("post-extract-model.fir"),
      new HighFirrtlToMiddleFirrtl,
      new MiddleFirrtlToLowFirrtl,
      // Dedup here in 'AQB form' after lowering instance bulk connects
      new midas.passes.EnableAndRunDedupOnce,
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
      new fame.EmitFAMEAnnotations("post-infer-model-ports.json"),
      new fame.FAMETransform,
      DefineAbstractClockGate,
      fame.AddRemainingFanoutAnnotations,
      new EmitFirrtl("post-fame-transform.fir"),
      new fame.EmitFAMEAnnotations("post-fame-transform.json"),
      new ResolveAndCheck,
      fame.MultiThreadFAME5Models,
      new ResolveAndCheck,
      new passes.InlineInstances,
      passes.ResolveKinds,
      new fame.EmitAndWrapRAMModels,
      new EmitFirrtl("post-gen-sram-models.fir"),
      new ResolveAndCheck,
      new SimulationMapping(state.circuit.main),
      xilinx.HostSpecialization,
      new ResolveAndCheck)
      (xforms foldLeft state)((in, xform) =>
      xform runTransform in).copy(form=outputForm)
  }
}
