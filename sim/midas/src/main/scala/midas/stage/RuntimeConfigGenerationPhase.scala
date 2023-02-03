// See LICENSE for license details.

package midas.stage

import midas.stage.phases.{CreateParametersInstancePhase, ConfigParametersAnnotation}
import midas.widgets.{BridgeIOAnnotation}
import midas.models.FASEDMemoryTimingModel
import midas.platform.PlatformShim
import midas.core.SimWrapperConfig

import freechips.rocketchip.diplomacy.LazyModule

import firrtl.ir.Port
import firrtl.annotations.{ReferenceTarget, NoTargetAnnotation}
import firrtl.AnnotationSeq
import firrtl.options.{Phase, Dependency}


// When the runtime config generation phase is being used, the
// BaseOutputFilenameAnnotation carries the complete name of the output file.
// This lets us reuse the Checks stage without custom handling,
private [stage] case class RuntimeConfigurationFile(body: String) extends NoTargetAnnotation with GoldenGateFileEmission {
  override def suffix = None
  def getBytes = body.getBytes
}

class RuntimeConfigGenerationPhase extends Phase {

  override val prerequisites = Seq(Dependency[CreateParametersInstancePhase])

  def transform(annotations: AnnotationSeq): AnnotationSeq = {
    implicit val p = annotations.collectFirst({ case ConfigParametersAnnotation(p)  => p }).get

    val bridgeIOAnnotations = annotations.collect { case b: BridgeIOAnnotation if b.widgetClass.nonEmpty => b }
    val shim = LazyModule(PlatformShim(SimWrapperConfig(bridgeIOAnnotations, Map[ReferenceTarget, Port]())))
    // Collect only FASEDBridges since they are the only ones that provide
    // runtime configuration generation
    val fasedBridges = shim.top.bridgeModuleMap.values.collect { case f: FASEDMemoryTimingModel  => f }
    // Since presently all memory models share the same runtime configuration. Grab only the first 
    // FASED BridgeAnnotation, and use that to elaborate a memory model
    val settings = fasedBridges.headOption.map({ lm =>
      lazy val fasedBridge = lm
      chisel3.stage.ChiselStage.elaborate(fasedBridge.module)
      fasedBridge.getSettings
    })

    RuntimeConfigurationFile(settings.getOrElse("\n")) +: annotations
  }
}
