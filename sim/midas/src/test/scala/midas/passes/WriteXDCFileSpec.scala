// See LICENSE for license details.

package midas.tests


import midas.passes._
import midas.targetutils.xdc._

import firrtl._
import firrtl.ir._
import firrtl.annotations._
import firrtl.testutils._

class WriteXDCFileSpec extends LowTransformSpec with FirrtlRunners with XDCAnnotationConstants  {
  def postLinkPath = "post/link"
  def pathToCircuitAnno = XDCPathToCircuitAnnotation(None, Some(postLinkPath))

  def checkPassRequirement(doWork: =>Unit): Unit =
    assertThrows[java.lang.IllegalArgumentException] {
      try {
        doWork
      } catch {
        case e: firrtl.CustomTransformException => println(e.getCause); throw e.getCause
      }
    }

  def transform = WriteXDCFile
  behavior of "WriteXDCFile"


  // Check that XDCPathToCircuitAnnotations are provided correctly
  {
    val input =
      """circuit Top :
        |  module Top :
        |    input clock : Clock
        """.stripMargin

    it should "fail when no XDCPathToCircuitAnnotations are provided" in {
      checkPassRequirement(executeWithAnnos(input, "", Nil, Nil))
    }
    it should "fail when more than one XDCPathToCircuitAnnotations are provided" in {
      checkPassRequirement(
        executeWithAnnos(input, "", Seq(pathToCircuitAnno, XDCPathToCircuitAnnotation(None,None)), Nil))
    }
  }

  // Destination file, rT in input, paths to instances
  type XDCSnippetSpec = (XDCDestinationFile, ReferenceTarget, Seq[String])

  def buildAnnotations(specs: XDCSnippetSpec*): (Seq[XDCAnnotation], Seq[XDCOutputAnnotation]) = {
    val xdcAnnos = for ((oFile, rT, _) <- specs) yield {
      XDCAnnotation(oFile, "{}", rT)
    }
    val outputAnnos = for ((oFile, annoSpecs) <- specs.groupBy(_._1)) yield {
      def prefix = if (oFile.preLink) "" else postLinkPath + "/"
      val body = (xdcHeader +: annoSpecs.flatMap(_._3.map { prefix + _ }))
        .mkString("\n")
      XDCOutputAnnotation(body, Some(oFile.fileSuffix))
    }
    (xdcAnnos.toSeq, outputAnnos.toSeq)
  }


  val input =
    """circuit Top :
      |  module Top :
      |    input clock : Clock
      |    inst a1 of A
      |    inst a2 of A
      |    a1.clock <= clock
      |    a2.clock <= clock
      |  module A :
      |    input clock : Clock
      """.stripMargin

  def mT = ModuleTarget("Top", "A")
  def rT = mT.ref("clock")

  it should "produce correct references across duplicated modules the synthesis XDC output" in {
    val (xdcAnnos, checkAnnos) = buildAnnotations(
      (XDCFiles.Synthesis, mT.ref("clock"), Seq("a1/clock", "a2/clock")))
    executeWithAnnos(input, input, pathToCircuitAnno +: xdcAnnos, checkAnnos)
  }

  it should "produce correct references across duplicated modules the implementation XDC output" in {
    val (xdcAnnos, checkAnnos) = buildAnnotations(
      (XDCFiles.Implementation, mT.ref("clock"), Seq("a1/clock", "a2/clock")))
    executeWithAnnos(input, input, pathToCircuitAnno +: xdcAnnos, checkAnnos)
  }

  it should "produce correct references when non-local RTs are used" in {
    val (xdcAnnos, checkAnnos) = buildAnnotations(
      (XDCFiles.Implementation, ModuleTarget("Top", "Top").instOf("a1", "A").ref("clock"), Seq("a1/clock")))
    executeWithAnnos(input, input, pathToCircuitAnno +: xdcAnnos, checkAnnos)
  }

  val input2 =
    """circuit Top :
      |  module Top :
      |    input clock : Clock
      |    inst a1 of A
      |    inst b of B
      |    a1.clock <= clock
      |    b.clock <= clock
      |  module B :
      |    input clock : Clock
      |    inst a2 of A
      |    inst a3 of A
      |    a2.clock <= clock
      |    a3.clock <= clock
      |  module A :
      |    input clock : Clock
      """.stripMargin

  it should "handle instances at multiple levels of the design hierarchy" in {
    val (xdcAnnos, checkAnnos) = buildAnnotations(
      (XDCFiles.Implementation, ModuleTarget("Top", "A").ref("clock"), Seq("a1/clock", "b/a2/clock", "b/a3/clock")))
    executeWithAnnos(input2, input2, pathToCircuitAnno +: xdcAnnos, checkAnnos)
  }
}
