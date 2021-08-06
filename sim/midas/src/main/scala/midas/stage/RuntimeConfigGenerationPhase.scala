// See LICENSE for license details.

package midas.stage

import midas.stage.phases.{CreateParametersInstancePhase, ConfigParametersAnnotation}
import midas.widgets.{BridgeIOAnnotation}
import midas.models.FASEDMemoryTimingModel
import midas.platform.PlatformShim

import freechips.rocketchip.diplomacy.{LazyModule, ValName}

import firrtl.ir.Port
import firrtl.annotations.{ReferenceTarget, NoTargetAnnotation}
import firrtl.{Transform, CircuitState, AnnotationSeq}
import firrtl.options.{Phase, TargetDirAnnotation, Dependency, CustomFileEmission}

import java.io.{File, FileWriter, Writer}
import logger._

private [stage] case class RuntimeConfigurationFile(name: String, body: String) extends NoTargetAnnotation with CustomFileEmission {
  override def suffix = None
  def baseFileName(annos: AnnotationSeq) = name
  def getBytes = body.getBytes
}

class RuntimeConfigGenerationPhase extends Phase {

  override val prerequisites = Seq(Dependency[CreateParametersInstancePhase])

  def transform(annotations: AnnotationSeq): AnnotationSeq = {
    val runtimeConfigName  = annotations.collectFirst({ case RuntimeConfigNameAnnotation(s) => s }).get
    implicit val p = annotations.collectFirst({ case ConfigParametersAnnotation(p)  => p }).get

    val bridgeIOAnnotations = annotations.collect { case b: BridgeIOAnnotation if b.widgetClass.nonEmpty => b }
    val shim = LazyModule(PlatformShim(bridgeIOAnnotations, Map[ReferenceTarget, Port]()))
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

    RuntimeConfigurationFile(runtimeConfigName, settings.getOrElse("\n")) +: annotations
  }
}
