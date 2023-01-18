//See LICENSE for license details.
//
package midas.passes

import midas.targetutils._

import scala.reflect.{ClassTag, classTag}
import scala.collection.mutable
import scala.util.{Try, Success, Failure}

import firrtl._
import firrtl.annotations._
import firrtl.options.{Dependency}
import firrtl.stage.{Forms}
import firrtl.passes.wiring.{SinkAnnotation, SourceAnnotation, WiringTransform}

/**
  * A type-parameterized wrapper for the WiringTransform that accepts a
  * specific Source and Sink annotation pair. It collects and maps these
  * specific annotations into WiringAnnotations.
  *
  * This permits sources and sinks for different features to exist without
  * having to wire them all at once (since some passes in the compiler may
  * inject new sinks or sources, before their opposite has been added).
  *
  * @param expectsExactlyOneSource When set, the pass will throw an exception
  * if no SourceType annotation is found. Otherwise, no changes to the circuit are
  * made (though SinkType annotations will be removed).
  *
  * @param expectsAtLeastOneSink When set, the pass will throw an exception
  * if no SinkType  annotations are found. Otherwise, no changes to the circuit are
  * made (though SourceType annotations will be removed).
  *
  */
class AnnotationParameterizedWiringTransform[
    SourceType <: SingleTargetAnnotation[ReferenceTarget] : ClassTag,
    SinkType <: SingleTargetAnnotation[ReferenceTarget] : ClassTag](
    expectsExactlyOneSource: Boolean,
    expectsAtLeastOneSink: Boolean) extends Transform with DependencyAPIMigration {

  override def prerequisites = Forms.MidForm
  override def optionalPrerequisites = Seq.empty
  override def optionalPrerequisiteOf = Forms.MidEmitters

  private val invalidates = Forms.VerilogOptimized.toSet -- Forms.MinimalHighForm
  override def invalidates(a: Transform): Boolean = invalidates(Dependency.fromTransform(a))

  def execute(state: CircuitState): CircuitState = {
    val wiringKey = classTag[SourceType].runtimeClass.getSimpleName
    val sources = mutable.ArrayBuffer[SourceAnnotation]()
    val sinks = mutable.ArrayBuffer[SinkAnnotation]()
    val cleanedAnnotations = mutable.ArrayBuffer[Annotation]()
    state.annotations.foreach {
      case a: SourceType => sources += SourceAnnotation(a.target.toNamed, wiringKey)
      case a: SinkType => sinks += SinkAnnotation(a.target.toNamed, wiringKey)
      case o => cleanedAnnotations += o
    }


    val sourceName = classTag[SourceType].runtimeClass.getName
    val sinkName = classTag[SinkType].runtimeClass.getName
    require(sources.size <= 1, s"Received multiple ${sourceName} annotations:\n" + sources.mkString("\n"))
    require(!expectsExactlyOneSource || sources.size == 1, s"Expected exactly one ${sourceName} annotation, but got none.")
    require(!expectsAtLeastOneSink   || sinks.size > 0, s"Expected at least one ${sinkName} annotation, but got none.")

    val sourceLoc = sources.headOption.map { _.target }
    val sinkLocs = sinks
      .map { _.target }
      .mkString("\n    ")

    logger.info(
      s"""|Wiring ${sourceName} to ${sinkName}
          |  Source: ${sourceLoc}
          |  Sinks: ${sinkLocs}""".stripMargin)


    def doWiring(): Try[CircuitState] = {
      if (sources.nonEmpty && sinks.nonEmpty) {
        val sinkWiringTransforms = Seq(new WiringTransform, new ResolveAndCheck)
        Try(sinkWiringTransforms.foldLeft(
          state.copy(annotations = sinks ++: sources ++: state.annotations))(
         (in, xform) => xform.runTransform(in)))
      } else {
        Success(state)
      }
    }

    doWiring() match {
      case Success(state) => state.copy(annotations = cleanedAnnotations.toSeq)
      case Failure(why) =>
        throw new RuntimeException(s"Could not perform wiring for annotation: ${wiringKey}. Exception follows.\n $why")
    }
  }
}

object GlobalResetConditionWiring extends AnnotationParameterizedWiringTransform[GlobalResetCondition, GlobalResetConditionSink](false, false)
