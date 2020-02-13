// See LICENSE for license details.

package midas.stage

import midas.{OutputDir}
import midas.widgets.{SerializableBridgeAnnotation}

import freechips.rocketchip.util.{ParsedInputNames}

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

    val fasedBridgeAnnos = annotations.collect({
      case anno @ SerializableBridgeAnnotation(_,_,className,_)
        if className == classOf[midas.models.FASEDMemoryTimingModel].getName => anno
    })
    // Since presently all memory models share the same runtime configuration. Grab only the first 
    // FASED BridgeAnnotation, and use that to elaborate a memory model
    fasedBridgeAnnos.headOption.map({ anno =>
      // Here we're spoofing elaboration that occurs in FPGATop, which assumes ExtractBridges has been run
      lazy val memModel = anno.toIOAnnotation("").elaborateWidget.asInstanceOf[midas.models.FASEDMemoryTimingModel]
      chisel3.Driver.elaborate(() => memModel)
      memModel.getSettings(runtimeConfigName)
    })
    annotations
  }
}
