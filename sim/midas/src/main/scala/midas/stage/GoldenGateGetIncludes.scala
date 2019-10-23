// See LICENSE for license details.

package midas.stage

import net.jcazevedo.moultingyaml._

import firrtl.AnnotationSeq
import firrtl.annotations.{AnnotationFileNotFoundException, LegacyAnnotation}
import firrtl.annotations.AnnotationYamlProtocol._
import firrtl.options.{InputAnnotationFileAnnotation, Phase, StageUtils}

import java.io.File

import scala.collection.mutable
import scala.util.{Try, Failure}

/**
  * Note: This file is copied from FIRRTL, and is modified only to use Golden Gate's custom serializers. 
  */

/** Recursively expand all [[GoldenGateInputAnnotationFileAnnotation]]s in an [[AnnotationSeq]] */
class GoldenGateGetIncludes extends Phase {

  /** Read all [[annotations.Annotation]] from a file in JSON or YAML format
    * @param filename a JSON or YAML file of [[annotations.Annotation]]
    * @throws annotations.AnnotationFileNotFoundException if the file does not exist
    */
  private def readAnnotationsFromFile(filename: String): AnnotationSeq = {
    val file = new File(filename).getCanonicalFile
    if (!file.exists) { throw new AnnotationFileNotFoundException(file) }
    midas.passes.fame.JsonProtocol.deserializeTry(file).recoverWith { case jsonException =>
      // Try old protocol if new one fails
      Try {
        val yaml = io.Source.fromFile(file).getLines().mkString("\n").parseYaml
        val result = yaml.convertTo[List[LegacyAnnotation]]
        val msg = s"$file is a YAML file!\n" + (" "*9) + "YAML Annotation files are deprecated! Use JSON"
        StageUtils.dramaticWarning(msg)
        result
      }.orElse { // Propagate original JsonProtocol exception if YAML also fails
        Failure(jsonException)
      }
    }.get
  }

  /** Recursively read all [[Annotation]]s from any [[GoldenGateInputAnnotationFileAnnotation]]s while making sure that each file is
    * only read once
    * @param includeGuard filenames that have already been read
    * @param annos a sequence of annotations
    * @return the original annotation sequence with any discovered annotations added
    */
  private def getIncludes(includeGuard: mutable.Set[String] = mutable.Set())
                         (annos: AnnotationSeq): AnnotationSeq = {
    annos.flatMap {
      case a @ GoldenGateInputAnnotationFileAnnotation(value) =>
        if (includeGuard.contains(value)) {
          StageUtils.dramaticWarning(s"Annotation file ($value) already included! (Did you include it more than once?)")
          None
        } else {
          includeGuard += value
          getIncludes(includeGuard)(readAnnotationsFromFile(value))
        }
      case x => Seq(x)
    }
  }

  def transform(annotations: AnnotationSeq): AnnotationSeq = {
    getIncludes()(annotations)
  }

}
