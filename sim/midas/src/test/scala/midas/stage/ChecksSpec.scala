// See LICENSE for license details.

package midas.stage

import org.scalatest.flatspec._
import firrtl.annotations._
import midas.stage._

import scala.reflect.{ClassTag}

class ChecksSpec extends AnyFlatSpec {

  behavior of "Command-line argument / input annotation checks"

  def baselineAnnos = Seq(
    OutputBaseFilenameAnnotation("A"),
    ConfigStringAnnotation("A"),
    ConfigPackageAnnotation("A"),
  )

  def runAndInterceptOptionsException(annos: Seq[Annotation]): Unit = {
    val e = intercept[firrtl.options.OptionsException] {
      midas.stage.Checks.transform(annos)
    }
    // Print the message for debug sanity
    println(e.getMessage)
  }

  /**
    * Checks the phase runs correctly when:
    * 1) an extra instance of the annotation is provided, and
    * 2) when all instances of the provided annotation type are removed
    *
    */
  def checkExactlyOne[T <: Annotation : ClassTag](extraAnno: T): Unit = {
    val className = extraAnno.getClass.getName
    it should s"reject passing multiple ${className}s" in {
      runAndInterceptOptionsException(extraAnno +: baselineAnnos)
    }

    val filteredAnnos = baselineAnnos.filterNot {
      case a: T => true
      case _ => false
    }

    it should s"reject passing zero ${className}s" in {
      runAndInterceptOptionsException(filteredAnnos)
    }
  }

  checkExactlyOne(OutputBaseFilenameAnnotation("B"))
  checkExactlyOne(ConfigStringAnnotation("B"))
  checkExactlyOne(ConfigPackageAnnotation("B"))
}
