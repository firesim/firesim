// See LICENSE

package midas.chiselstage.stage.phases

import chisel3.stage.ChiselOutputFileAnnotation
import firrtl.AnnotationSeq
import firrtl.options.Viewer.view
import firrtl.options.{Dependency, Phase, PreservesAll}
import midas.chiselstage.stage._

/** Transforms RocketChipAnnotations into those used by other stages */
class TransformAnnotations extends Phase with PreservesAll[Phase] with HasMidasStageUtils {

  override val prerequisites = Seq(Dependency[Checks])
  override val dependents    = Seq(Dependency[chisel3.stage.phases.AddImplicitOutputFile])

  override def transform(annotations: AnnotationSeq): AnnotationSeq = {

    /** Construct output file annotation for emission */
    new ChiselOutputFileAnnotation(view[MidasOptions](annotations).longName.get) +: annotations
  }
}
