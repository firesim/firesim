// See LICENSE for license details.

package midas.stage

import org.scalatest.flatspec._
import midas.stage._

class AddDerivedAnnotationsSpec extends AnyFlatSpec {

  behavior of "AddDerivedAnnotations Phase"

  def baseFilename = "a"
  def obfAnno = OutputBaseFilenameAnnotation(baseFilename)

  def checkAnnos(extraAnnos: Seq[firrtl.annotations.Annotation]): Unit = {
    val annos = midas.stage.AddDerivedAnnotations.transform(obfAnno +: extraAnnos)
    val ofAnnos = annos.collect { case a: firrtl.stage.OutputFileAnnotation => a }
    val ecAnnos = annos.collect { case a: firrtl.EmitCircuitAnnotation => a}
    assert(ofAnnos.size == 1)
    assert(ofAnnos.head == firrtl.stage.OutputFileAnnotation(s"${baseFilename}.sv"))
    assert(ecAnnos.size == 1)
    assert(ecAnnos.head == firrtl.EmitCircuitAnnotation(classOf[firrtl.SystemVerilogEmitter]))
  }

  it should "produce an EmitCircuitAnnotation and the correct OutputFileAnnotation" in {
    checkAnnos(Seq())
  }

  it should "remove existing OutputFileAnnotations" in {
    checkAnnos(Seq(firrtl.stage.OutputFileAnnotation("BAD")))
  }

  it should "remove existing EmitCircuitAnnotations" in {
    checkAnnos(Seq(firrtl.EmitCircuitAnnotation(classOf[firrtl.VerilogEmitter])))
  }
}
