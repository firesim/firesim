// See LICENSE for license details.

package midas.stage

import firrtl.AnnotationSeq
import firrtl.annotations.Annotation
import firrtl.options.{OptionsException, Phase, HasShellOptions}

import scala.reflect.{ClassTag, classTag}
import scala.collection.mutable

/** Sanity checks an [[firrtl.AnnotationSeq]] before running the main [[firrtl.options.Phase]]s of
  * [[chisel3.stage.ChiselStage]].
  */
object Checks extends Phase {

  override def prerequisites = Seq.empty
  override def optionalPrerequisites = Seq.empty
  override def optionalPrerequisiteOf = Seq.empty
  override def invalidates(a: Phase) = false

  def transform(annotations: AnnotationSeq): AnnotationSeq = {

    def multipleCopiesError(className: String, optionList: String, numCopies: Int): String =
      s"""|Exactly one ${className} must be specified, but found ${numCopies}. Did you duplicate:
          |    ${optionList}
          |""".stripMargin

    def noCopiesError(className: String, optionList: String): String =
      s"""|Exactly one ${className} must be specified, but found none. Please provide one of the following options or annotations:
          |    ${optionList}
          |""".stripMargin

    def stringOfOptions(annoName: String, shellOption: HasShellOptions): String = {
      assert(shellOption.options.size == 1)
      val option = shellOption.options.head
      val allOptions =
        option.shortOption.map { "-" + _ } ++:
        Seq("--" + option.longOption, annoName)
      allOptions.mkString(", ")
    }

    def assertExactlyOne[A <: Annotation : ClassTag, B <: HasShellOptions](list: mutable.ListBuffer[A], shellOption: B): Unit = {
      val annoName = classTag[A].runtimeClass.getName
      val optionStr = stringOfOptions(annoName, shellOption)

      if (list.isEmpty) {
        throw new OptionsException(noCopiesError(annoName, optionStr))
      } else if (list.size > 1) {
        throw new OptionsException(multipleCopiesError(annoName, optionStr, list.size))
      }
    }

    val cpa = mutable.ListBuffer[ConfigPackageAnnotation]()
    val csa = mutable.ListBuffer[ConfigStringAnnotation]()
    val obf = mutable.ListBuffer[OutputBaseFilenameAnnotation]()
    annotations.foreach {
      case a: ConfigPackageAnnotation => a +=: cpa
      case a: ConfigStringAnnotation => a +=: csa
      case a: OutputBaseFilenameAnnotation => a +=: obf
      case _ =>
    }

    assertExactlyOne(cpa, ConfigPackageAnnotation)
    assertExactlyOne(csa, ConfigStringAnnotation)
    assertExactlyOne(obf, OutputBaseFilenameAnnotation)

    annotations
  }
}
