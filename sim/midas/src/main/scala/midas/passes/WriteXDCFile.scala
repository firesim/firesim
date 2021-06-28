// See LICENSE for license details.

package midas.passes

import java.io.{File, FileWriter}
import firrtl._
import firrtl.stage.Forms
import firrtl.annotations._
import firrtl.analyses.InstanceKeyGraph

import midas.stage.XDCOutputNameAnnotation
import midas.targetutils.{XDCAnnotation, XDCAnnotationConstants}

private[midas] object WriteXDCFile extends Transform with DependencyAPIMigration with XDCAnnotationConstants {
	override def prerequisites = Forms.LowForm
  // Probably should run before emitter?

  private def formatArguments(iGraph: InstanceKeyGraph, argumentList: Iterable[ReferenceTarget]): Iterable[String] = {
    for (arg <- argumentList) yield {
      val instances = iGraph.findInstancesInHierarchy(arg.module)
      require(instances.length == 1)
      val tokens =
        instances.head.tail.map { _.Instance.value } ++: // Path to root
        arg.path.map { _._1.value } ++: // Path in the provided RT
        arg.ref +: arg.component.map { _.value }
      tokens.mkString("/")
    }
  }

  private def serializeXDC(anno: XDCAnnotation, iGraph: InstanceKeyGraph): String = {
    val segments = specifierRegex.split(anno.formatString)
    val formattedArguments = formatArguments(iGraph, anno.argumentList)
    // :scala emoji:
    segments.zipAll(formattedArguments, "", "").foldLeft(""){ case (root, (a, b)) => root + a + b }
  }

  def execute(state: CircuitState): CircuitState = {
    val targetDir = state.annotations.collectFirst({
      case TargetDirAnnotation(dir) => new File(dir)
    }).get

    val outputFile = state.annotations.collectFirst({
      case XDCOutputNameAnnotation(name) => new File(targetDir, name)
    }).get

    val iGraph = InstanceKeyGraph(state.circuit)

    val xdcStrings = state.annotations.collect { case a: XDCAnnotation => serializeXDC(a, iGraph) }

    val fw = new FileWriter(outputFile)
    fw.write(xdcStrings.mkString("\n"))
    fw.close

    val cleanedAnnotations = state.annotations.filterNot {
      case a: XDCOutputNameAnnotation => true
      case a: XDCAnnotation => true
      case _ => false
    }
    state.copy(annotations = cleanedAnnotations)
  }
}
