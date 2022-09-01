// See LICENSE for license details.

package midas.stage

import firrtl.AnnotationSeq
import firrtl.annotations.{NoTargetAnnotation, Annotation}
import firrtl.options.{CustomFileEmission}

import chisel3.experimental.{annotate, ChiselAnnotation}

object DownstreamFlows {
  sealed trait FlowType 
  case object BitstreamCompile extends FlowType
  case object MetasimulatorCompile extends FlowType
  case object DriverCompile extends FlowType
  case object RuntimeDeployment extends FlowType
  val allFlows = Seq(BitstreamCompile, MetasimulatorCompile, RuntimeDeployment, DriverCompile)

  val fileManifestSuffix = ".file-manifest.json"
  val emittedVerilogKey = "EmittedVerilogFiles"
  val unclassifiedKey   = "UnclassifiedFiles"
}

trait GoldenGateFileEmission extends CustomFileEmission { this: Annotation =>
  override def baseFileName(annotations: firrtl.AnnotationSeq) = {
    annotations.collectFirst{ case OutputBaseFilenameAnnotation(name) => name }.get
  }
  /** Used to build a manifest that organizes output files based on what downstream flows consume it. **/
  def downstreamDependencies: Set[DownstreamFlows.FlowType]
}

/**
  * A generic wrapper for output files that have no targets.
  *
  * @param body the body of the file
  * @param fileSuffix The string to append to base output file name
  *
  */
case class GoldenGateOutputFileAnnotation(
  body: String,
  fileSuffix: String,
  downstreamDependencies: Set[DownstreamFlows.FlowType])
    extends NoTargetAnnotation with GoldenGateFileEmission {
  def suffix = Some(fileSuffix)
  def getBytes = body.getBytes
}

object GoldenGateOutputFileAnnotation {
  /**
    * Sugar to add a new output file from a chisel source (e.g., in a bridge, platform shim)
    */
  def annotateFromChisel(body: String, fileSuffix: String, downstreamDependencies: Set[DownstreamFlows.FlowType]): Unit = {
    annotate(new ChiselAnnotation { def toFirrtl = GoldenGateOutputFileAnnotation(body, fileSuffix, downstreamDependencies) })
  }
}

/**
  * Wraps a StringBuilder to incrementally build up an output file annotation.
  */
class OutputFileBuilder(header: String, fileSuffix: String, downstreamDependencies: Set[DownstreamFlows.FlowType]) {
  private val sb = new StringBuilder(header)
  def getBuilder = sb
  def append(str: String) = sb.append(str)
  def toAnnotation = GoldenGateOutputFileAnnotation(sb.toString, fileSuffix, downstreamDependencies)
}
