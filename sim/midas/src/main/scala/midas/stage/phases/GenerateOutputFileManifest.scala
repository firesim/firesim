// See LICENSE for license details.

package midas.stage.phases

import midas.stage.{GoldenGateFileEmission, GoldenGateOutputFileAnnotation, DownstreamFlows}
import firrtl.{AnnotationSeq, EmittedVerilogCircuitAnnotation, EmittedVerilogModuleAnnotation}
import firrtl.options.{TargetDirAnnotation, Phase, Dependency, CustomFileEmission}

import org.json4s.native.Serialization

import collection.immutable.ListMap

/**
  * Extracts a TargetDir-relative path to an EmittedFile.
  */
object TargetDirRelativePath {
  def apply(emittedAnno: CustomFileEmission, annotations: AnnotationSeq): String = {
    val targetDir  = annotations.collectFirst { case TargetDirAnnotation(name) => name }.get
    emittedAnno.filename(annotations).getAbsolutePath.stripPrefix(s"${targetDir}/")
  }
}

/** Emits an manifest file that sorts all known output files into groups based on 
 * where those files will be used. See [[midas.stage.DownstreamFlows]].
 */
object GenerateOutputFileManifest extends Phase {

  override val prerequisites = Seq(Dependency[midas.stage.GoldenGateCompilerPhase])

  def transform(annotations: AnnotationSeq): AnnotationSeq = {
    val ggOutputFiles   = annotations.collect { case anno: GoldenGateFileEmission => anno }

    // Group GG annotations based on the flows in which they are used.
    val unclassifiedFiles = "Unclassified" -> ggOutputFiles.filter(_.downstreamDependencies.isEmpty)
    val classifiedFiles   = DownstreamFlows.allFlows.map { tpe =>
      tpe.toString -> ggOutputFiles.filter(_.downstreamDependencies.contains(tpe)) 
    }

    val ggFileTuples = for ((flowType, fileAnnos) <- (classifiedFiles :+ unclassifiedFiles)) yield {
      flowType -> fileAnnos.map { anno => TargetDirRelativePath(anno, annotations) }
    }

    // Capture all verilog files (GG does not control emission of these) and put them in a seperate field
    val verilogCircuitPath  = annotations.collectFirst { case anno: EmittedVerilogCircuitAnnotation => TargetDirRelativePath(anno, annotations) }
    val verilogModulesPath  = annotations.collect      { case anno: EmittedVerilogModuleAnnotation  => TargetDirRelativePath(anno, annotations) }
    val emittedVerilogTuple = (DownstreamFlows.emittedVerilogKey -> (verilogCircuitPath ++: verilogModulesPath).toSeq )


    implicit val formats = org.json4s.DefaultFormats
    val body = Serialization.writePretty(ListMap((ggFileTuples :+ emittedVerilogTuple):_*))
    GoldenGateOutputFileAnnotation(body, DownstreamFlows.fileManifestSuffix, Set()) +: annotations
  }
}
