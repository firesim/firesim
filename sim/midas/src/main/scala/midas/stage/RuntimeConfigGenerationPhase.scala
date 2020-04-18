// See LICENSE for license details.

package midas.stage

import midas.{OutputDir}
import midas.widgets.{BridgeIOAnnotation}
import midas.models.FASEDMemoryTimingModel
import midas.platform.PlatformShim

import freechips.rocketchip.diplomacy.{LazyModule, ValName}
import midas.rocketchip.util.{ParsedInputNames}

import firrtl.ir.Port
import firrtl.annotations.ReferenceTarget
import firrtl.{Transform, CircuitState, AnnotationSeq}
import firrtl.options.{Phase, TargetDirAnnotation}

import java.io.{File, FileWriter, Writer}
import logger._

class RuntimeConfigGenerationPhase extends Phase with ConfigLookup {

  def transform(annotations: AnnotationSeq): AnnotationSeq = {
    val targetDir = annotations.collectFirst({ case TargetDirAnnotation(targetDir) => new File(targetDir) }).get
    val configPackage = annotations.collectFirst({ case ConfigPackageAnnotation(p) => p }).get
    val configString  = annotations.collectFirst({ case ConfigStringAnnotation(s) => s }).get
    val runtimeConfigName  = annotations.collectFirst({ case RuntimeConfigNameAnnotation(s) => s }).get

    val pNames = ParsedInputNames("UNUSED", "UNUSED", "UNUSED", configPackage, configString, None)

    implicit val p = getParameters(pNames).alterPartial({
      case OutputDir => targetDir
    })

    val bridgeIOAnnotations = annotations.collect { case b: BridgeIOAnnotation if b.widgetClass.nonEmpty => b }
    val shim = LazyModule(PlatformShim(bridgeIOAnnotations, Map[ReferenceTarget, Port]()))
    // Collect only FASEDBridges since they are the only ones that provide
    // runtime configuration generation
    val fasedBridges = shim.top.bridgeModuleMap.values.collect { case f: FASEDMemoryTimingModel  => f }
    // Since presently all memory models share the same runtime configuration. Grab only the first 
    // FASED BridgeAnnotation, and use that to elaborate a memory model
    fasedBridges.headOption.map({ lm =>
      lazy val fasedBridge = lm.module
      chisel3.Driver.elaborate(() => fasedBridge)
      fasedBridge.getSettings(runtimeConfigName)
    })
    annotations
  }
}
