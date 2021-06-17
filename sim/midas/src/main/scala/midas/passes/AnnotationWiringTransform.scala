//See LICENSE for license details.
//
package midas.passes

import midas.targetutils._

import scala.reflect.{ClassTag, classTag}
import scala.collection.mutable

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
  */
class AnnotationParameterizedWiringTransform[
  SourceType <: SingleTargetAnnotation[ReferenceTarget] : ClassTag,
  SinkType <: SingleTargetAnnotation[ReferenceTarget] : ClassTag] extends Transform with DependencyAPIMigration {

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

    assert(sources.size <= 1, "Expected 0 or 1 sources, got:\n" + sources.mkString("\n"))

    val updatedState = if (sources.nonEmpty) {
      val sinkWiringTransforms = Seq(new WiringTransform, new ResolveAndCheck)
      sinkWiringTransforms.foldLeft(
        state.copy(annotations = sinks ++: sources ++: state.annotations))(
       (in, xform) => xform.runTransform(in))
    } else {
      state
    }
    updatedState.copy(annotations = cleanedAnnotations)
  }
}

object GlobalResetConditionWiring extends AnnotationParameterizedWiringTransform[GlobalResetCondition, GlobalResetConditionSink]
