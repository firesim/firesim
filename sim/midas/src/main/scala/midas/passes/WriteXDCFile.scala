// See LICENSE for license details.

package midas.passes

import firrtl._
import firrtl.stage.Forms
import firrtl.annotations._
import firrtl.analyses.InstanceKeyGraph

import midas.stage.{GoldenGateFileEmission}
import midas.targetutils.xdc._

/**
  * We could reuse [[GoldenGateOutputFileAnnotation]] here, but this makes it
  * marginally easier to filter out.
  */
private[midas] case class XDCOutputAnnotation(fileBody: String, suffix: Option[String])
    extends NoTargetAnnotation with GoldenGateFileEmission {
  def getBytes = fileBody.getBytes
}

private[midas] object WriteXDCFile extends Transform with DependencyAPIMigration with XDCAnnotationConstants {
    override def prerequisites = Forms.LowForm

  private def formatArguments(
      iGraph: InstanceKeyGraph,
      argumentList: Iterable[ReferenceTarget],
      pathToCircuit: Option[String]): Iterable[Iterable[String]] = {

    // Just skip empty argument lists.
    if (argumentList.isEmpty) {
      return Seq(Seq())
    }

    // Avoid the complexity of managing duplication of targets that don't
    // explicitly agree on a root module.
    //
    // A better approach would be to find the LCA of all targets, and ensure none of them
    // are duplicated under that ancestor. In practice, XDC snippets will be
    // emitted with targets rooted at the current chisel module...
    val rootModule = argumentList.head.module
    // Explicit here means the LCA is encoded directly in the module field of the target.
    val hasCommonExplicitRoot = argumentList.forall(_.module == rootModule)
    require(hasCommonExplicitRoot,
      "All targets in an XDC Annotation must be rooted at the same module. Got:\n" +
      argumentList.mkString("\n"))

    // Get local paths for each reference under the enclosing module
    val relativePaths = for (arg <- argumentList) yield {
      arg.path.map { _._1.value } ++: // Path in the provided RT
      arg.ref +:
      arg.component.map { _.value }
    }
    // Prepare to duplicate the XDC snippet for each instance of the root module
    val instances = iGraph.findInstancesInHierarchy(rootModule)
    for (instPath <- instances) yield {
      val fullInstPath = pathToCircuit ++: instPath.tail.map { _.Instance.value }
      for (rPath <- relativePaths) yield {
        (fullInstPath ++ rPath).mkString("/")
      }
    }
  }

  private def serializeXDC(
      anno: XDCAnnotation,
      iGraph: InstanceKeyGraph,
      pathToCircuit: Option[String]): Iterable[String] = {
    val segments = specifierRegex.split(anno.formatString)
    val duplicatedArgumentLists = formatArguments(iGraph, anno.argumentList, pathToCircuit)
    for (formattedArguments <- duplicatedArgumentLists) yield {
      segments.zipAll(formattedArguments, "", "").map { case (a, b) => a + b }.mkString
    }
  }

  def execute(state: CircuitState): CircuitState = {

    // Detect if our circuit is nested, and prepend the provided path to
    // emitted reference targets.
    val circuitPathMappings = state.annotations.collect { case XDCPathToCircuitAnnotation(pre, post) => (pre, post) }
    require(circuitPathMappings.size == 1,
      s"Exactly one PathToCircuitAnnotations required. Got ${circuitPathMappings.size}.")

    val iGraph = InstanceKeyGraph(state.circuit)
    val xdcAnnosGroupedByFile = state.annotations
      .collect { case a: XDCAnnotation => a }
      .groupBy { _.destinationFile }
      .toMap

    val (preLinkPath, postLinkPath) = circuitPathMappings.head
    val outputAnnos = for (fileType <- XDCFiles.allFiles) yield {
      val circuitPath = if (fileType.preLink) preLinkPath else postLinkPath
      val annos = xdcAnnosGroupedByFile.get(fileType).getOrElse(Nil)
      val xdcSnippets = annos.map { a => serializeXDC(a, iGraph, circuitPath) }
      XDCOutputAnnotation(
        (xdcHeader +: xdcSnippets.flatten).mkString("\n"),
        Some(fileType.fileSuffix))
    }

    val cleanedAnnotations = state.annotations.filterNot {
      case a: XDCAnnotation => true
      case a: XDCPathToCircuitAnnotation => true
      case _ => false
    }
    state.copy(annotations = outputAnnos ++: cleanedAnnotations)
  }
}
