//See LICENSE for license details.

package midas.passes.fame

import midas.widgets.SerializableBridgeAnnotation

import firrtl.annotations._

import scala.util.{Try, Failure}

import org.json4s._
import org.json4s.native.JsonMethods._
import org.json4s.native.Serialization
import org.json4s.native.Serialization.{read, writePretty}

case class DeserializationTypeHintsAnnotation(typeTags: Seq[String]) extends NoTargetAnnotation

trait HasSerializationHints {
  // For serialization of complicated constuctor arguments, let the bridge
  // designer specify additional type hints for relevant classes that might be
  // contained within
  def typeHints(): Seq[Class[_]]
}

object JsonProtocol {
  import firrtl.annotations.JsonProtocol._

  /** Construct Json formatter for annotations */
  def jsonFormat(tags: Seq[Class[_]]) = {
    Serialization.formats(FullTypeHints(tags.toList)).withTypeHintFieldName("class") +
      new TransformClassSerializer + new NamedSerializer + new CircuitNameSerializer +
      new ModuleNameSerializer + new ComponentNameSerializer + new TargetSerializer +
      new GenericTargetSerializer + new CircuitTargetSerializer + new ModuleTargetSerializer +
      new InstanceTargetSerializer + new ReferenceTargetSerializer + new TransformSerializer  +
      new LoadMemoryFileTypeSerializer
  }

  /** Serialize annotations to a String for emission */
  def serialize(annos: Seq[Annotation]): String = serializeTry(annos).get

  def serializeTry(annos: Seq[Annotation]): Try[String] = {
    val tags = annos.flatMap({
      case anno: HasSerializationHints => anno.getClass +: anno.typeHints
      case other => Seq(other.getClass)
    }).distinct

    implicit val formats = jsonFormat(classOf[DeserializationTypeHintsAnnotation] +: tags)
    Try(writePretty(DeserializationTypeHintsAnnotation(tags.map(_.getName)) +: annos))
  }

  def deserialize(in: JsonInput): Seq[Annotation] = deserializeTry(in).get

  def deserializeTry(in: JsonInput): Try[Seq[Annotation]] = Try({
    val parsed = parse(in)
    val annos = parsed match {
      case JArray(objs) => objs
      case x => throw new InvalidAnnotationJSONException(
        s"Annotations must be serialized as a JArray, got ${x.getClass.getSimpleName} instead!")
    }
    // Gather classes so we can deserialize arbitrary Annotations
    val classes = annos.flatMap({
      case JObject(("class", JString(typeHintAnnoName)) :: ("typeTags", JArray(classes)) :: Nil) =>
          typeHintAnnoName +: classes.collect({ case JString(className) => className })
      case JObject(("class", JString(c)) :: tail) => Seq(c)
      case obj => throw new InvalidAnnotationJSONException(s"Expected field 'class' not found! $obj")
    }).distinct
    val loaded = classes.map(Class.forName(_).asInstanceOf[Class[_ <: Annotation]])

    implicit val formats = jsonFormat(loaded)
    read[List[Annotation]](in)
  }).recoverWith {
    // Translate some generic errors to specific ones
    case e: java.lang.ClassNotFoundException =>
      Failure(new AnnotationClassNotFoundException(e.getMessage))
    case e: org.json4s.ParserUtil.ParseException =>
      Failure(new InvalidAnnotationJSONException(e.getMessage))
  }.recoverWith { // If the input is a file, wrap in InvalidAnnotationFileException
    case e: firrtl.FirrtlUserException => in match {
      case FileInput(file) =>
        Failure(new InvalidAnnotationFileException(file, e))
      case _ => Failure(e)
    }
  }
}
