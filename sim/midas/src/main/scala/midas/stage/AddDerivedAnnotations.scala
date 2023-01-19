// See LICENSE for license details.

package midas.stage

import firrtl.AnnotationSeq
import firrtl.options.{Phase, Dependency}

import scala.collection.mutable

/**
  *  Provides [[firrtl.stage.OutputFileAnnotation]] and
  *  [[firrtl.EmitCircuitAnnotation]], which are required by downstream FIRRTL
  *  transforms but derive from Golden Gate-specific annotations.
  */
object AddDerivedAnnotations extends Phase {

  override def prerequisites = Seq(Dependency(midas.stage.Checks))
  override def optionalPrerequisites = Seq.empty
  override def optionalPrerequisiteOf = Seq(Dependency[midas.stage.GoldenGateCompilerPhase])
  override def invalidates(a: Phase) = false

  def transform(annotations: AnnotationSeq): AnnotationSeq = {

    val obfAnnos = mutable.ListBuffer[OutputBaseFilenameAnnotation]()
    val filteredAnnos = annotations.flatMap {
      case o: firrtl.stage.OutputFileAnnotation =>
        logger.warn("firrtl.stage.OutputFileAnnotation found but ignored. Output names are derived from the OutputBaseFilenameAnnotation.")
        None
      case e: firrtl.EmitCircuitAnnotation =>
        logger.warn("firrtl.EmitCircuitAnnotation found but ignored. Output emission is fixed to SystemVerilog.")
        None
      case obf: OutputBaseFilenameAnnotation =>
        obf +=: obfAnnos
        Some(obf)
      case a => Some(a)
    }

    // Should be checked in midas.stage.Checks
    assert(obfAnnos.size == 1)
    Seq(
      firrtl.stage.OutputFileAnnotation(s"${obfAnnos.head.name}.sv"),
      firrtl.EmitCircuitAnnotation(classOf[firrtl.SystemVerilogEmitter])
    ) ++: filteredAnnos
  }
}
