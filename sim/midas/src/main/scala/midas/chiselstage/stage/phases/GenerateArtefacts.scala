// See LICENSE

package midas.chiselstage.stage.phases

import firrtl.AnnotationSeq
import firrtl.options.{Dependency, Phase, PreservesAll, StageOptions}
import firrtl.options.Viewer.view
import midas.chiselstage.stage._
import freechips.rocketchip.util.ElaborationArtefacts

/** Writes [[ElaborationArtefacts]] into files */
class GenerateArtefacts extends Phase with PreservesAll[Phase] with HasMidasStageUtils {

  override val prerequisites = Seq(Dependency[midas.chiselstage.stage.MidasChiselStage])

  override def transform(annotations: AnnotationSeq): AnnotationSeq = {
    val targetDir = view[StageOptions](annotations).targetDir

    ElaborationArtefacts.files.foreach { case (extension, contents) =>
      writeOutputFile(targetDir, s"${view[MidasOptions](annotations).longName.get}.${extension}", contents())
    }

    annotations
  }

}
