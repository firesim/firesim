// See LICENSE for license details.

package midas.stage

import firrtl.annotations.{NoTargetAnnotation, Annotation}
import firrtl.options.{CustomFileEmission}

trait GoldenGateFileEmission extends CustomFileEmission { this: Annotation =>

  // workaround CustomFileEmission's requirement that suffixes begin with ".".
  // by shadowing the same feature, and appending it ourselves. This is the
  // easiest way to preserve most of the existing output file names.
  def fileSuffix: String
  final def suffix = None

  override def baseFileName(annotations: firrtl.AnnotationSeq) = {
    val baseName = annotations.collectFirst{ case OutputBaseFileNameAnnotation(name) => name }.get
    baseName + fileSuffix
  }
}

/**
  * A generic wrapper for output files that have no targets.
  */
case class GoldenGateOutputFileAnnotation(body: String, fileSuffix: String)
    extends NoTargetAnnotation with GoldenGateFileEmission {
  def getBytes = body.getBytes
}

/**
  * Wraps a StringBuilder to incrementally build up an output file annotation.
  */
class OutputFileBuilder(header: String, fileSuffix: String) {
  private val sb = new StringBuilder(header)
  def getBuilder = sb
  def append(str: String) = sb.append(str)
  def toAnnotation = GoldenGateOutputFileAnnotation(sb.toString, fileSuffix)
}
