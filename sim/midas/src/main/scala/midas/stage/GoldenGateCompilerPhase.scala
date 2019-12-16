// See LICENSE for license details.

package midas.stage

import midas._
import midas.passes.{MidasCompiler, HostTransformCompiler, LastStageVerilogCompiler}

import firrtl.ir.Circuit
import firrtl.{Transform, CircuitState, AnnotationSeq}
import firrtl.annotations.{Annotation}
import firrtl.options.{Phase, TargetDirAnnotation}
import firrtl.stage.{FirrtlCircuitAnnotation}
import firrtl.CompilerUtils.getLoweringTransforms
import firrtl.passes.memlib._

import freechips.rocketchip.config.{Parameters, Config, Field}
import freechips.rocketchip.util.{ParsedInputNames}
import java.io.{File, FileWriter, Writer}
import logger._

class GoldenGateCompilerPhase extends Phase with ConfigLookup {

  def transform(annotations: AnnotationSeq): AnnotationSeq = {
    val allCircuits = annotations.collect({ case FirrtlCircuitAnnotation(circuit) => circuit })
    require(allCircuits.size == 1, "Golden Gate can only process a single Firrtl Circuit at a time.")
    val circuit = allCircuits.head

    val targetDir = annotations.collectFirst({ case TargetDirAnnotation(targetDir) => new File(targetDir) }).get
    val configPackage = annotations.collectFirst({ case ConfigPackageAnnotation(p) => p }).get
    val configString  = annotations.collectFirst({ case ConfigStringAnnotation(s) => s }).get
    val pNames = ParsedInputNames("UNUSED", "UNUSED", "UNUSED", configPackage, configString, None)

    val midasAnnos = Seq(InferReadWriteAnnotation)

    implicit val p = getParameters(pNames).alterPartial({
      case OutputDir => targetDir
    })
    // Ran prior to Golden Gate tranforms (target-time)
    val targetTransforms = p(TargetTransforms).flatMap(transformCtor => transformCtor(p))
    // Ran after Golden Gate transformations (host-time)
    val hostTransforms = p(HostTransforms).flatMap(transformCtor => transformCtor(p))
    val midasTransforms = new passes.MidasTransforms()
    val compiler = new MidasCompiler
    val midas = compiler.compile(firrtl.CircuitState(
      circuit, firrtl.HighForm, annotations ++ midasAnnos),
      targetTransforms :+ midasTransforms)

    val postHostTransforms = new HostTransformCompiler().compile(midas, hostTransforms)
    val result = new LastStageVerilogCompiler().compileAndEmit(postHostTransforms, Seq())
    result.annotations
  }

}
