// See LICENSE for license details.

package midas.widgets

import chisel3._
import firrtl.annotations.{SingleTargetAnnotation} // Deprecated
import firrtl.annotations.{ReferenceTarget, ModuleTarget}
import freechips.rocketchip.config.Parameters
import midas.passes.fame.{JsonProtocol, HasSerializationHints}

trait BridgeAnnotation extends SingleTargetAnnotation[ModuleTarget] {
  // A list of channel names that match the globalName emitted in the FCCAs
  // associated with this bridge. We use these strings to look up those annotations
  def channelNames: Seq[String]
  // Invoked by BridgeExtraction to convert this ModuleTarget-based annotation into
  // a ReferenceTarget based one that can be attached to newly created IO on the top-level
  def toIOAnnotation(port: String): BridgeIOAnnotation
}

/**
  * A serializable form of BridgeAnnotation emitted by Chisel Modules that extend Bridge
  *
  * @param target  The module representing an Bridge. Typically a black box
  *
  * @param channelNames  See BridgeAnnotation. A list of channelNames used
  *  to find associated FCCAs for this bridge
  *
  * @param widgetClass  The full class name of the BridgeModule generator
  *
  * @param widgetConstructorKey A optional, serializable object which will be passed 
  *   to the constructor of the BridgeModule. Consult https://github.com/json4s/json4s#serialization to 
  *   better understand what can and cannot be serialized.
  *
  *   To provide additional typeHints to the serilization/deserialization
  *   protocol mix in HasSerializationHints into your ConstructorKey's class and return 
  *   additional pertinent classes
  */

case class SerializableBridgeAnnotation[T <: AnyRef](
    val target: ModuleTarget,
    channelNames: Seq[String],
    widgetClass: String,
    widgetConstructorKey: Option[T])
  extends BridgeAnnotation with HasSerializationHints {

  def typeHints() = widgetConstructorKey match {
    // If the key has extra type hints too, grab them as well
    case Some(key: HasSerializationHints) => key.getClass +: key.typeHints
    case Some(key) => Seq(key.getClass)
    case None => Seq()
  }
  def duplicate(n: ModuleTarget) = this.copy(target)
  def toIOAnnotation(port: String): BridgeIOAnnotation = {
    val channelMapping = channelNames.map(oldName => oldName -> s"${port}_$oldName")
    BridgeIOAnnotation(target.copy(module = target.circuit).ref(port),
      channelMapping.toMap,
      widgetClass = Some(widgetClass),
      widgetConstructorKey = widgetConstructorKey)
  }
}

/**
  * A form BridgeAnnotation that can be emitted by FIRRTL transforms that
  * run during Golden Gate compilation. Since these do not need to be
  * serialized, BridgeModule generation can be more flexibly captured in a lambda
  *
  *
  * @param target  The module representing an Bridge. Typically a black box
  *
  * @param channelNames  See BridgeAnnotation. A list of channelNames used
  *  to find associated FCCAs for this bridge
  *
  * @param widget  A lambda to elaborate the host-land BridgeModule in FPGATop
  *
  */

case class InMemoryBridgeAnnotation(
    val target: ModuleTarget,
    channelNames: Seq[String],
    widget: (Parameters) => BridgeModule[_ <: TokenizedRecord]) extends BridgeAnnotation {
  def duplicate(n: ModuleTarget) = this.copy(target)
  def toIOAnnotation(port: String): BridgeIOAnnotation = {
    val channelMapping = channelNames.map(oldName => oldName -> s"${port}_$oldName")
    BridgeIOAnnotation(target.copy(module = target.circuit).ref(port),
      channelMapping.toMap,
      widget = Some(widget))
   }
}

/**
  * An BridgeAnnotation that references the IO created by BridgeExtraction after it has promoted and removed
  * all modules annotated with BridgeAnnotations.
  *
  * Annotations that originate from SerializableBridgeAnnotation will have widget = None
  * Annotations taht originate from InMemoryBridgeAnnotation will set widgetClass, widgetConstructorKey = None
  *
  * @param target  The IO corresponding to and Bridge's interface
  *
  * @param channelMapping A mapping from the channel names initially emitted by the Chisel Module, to uniquified global ones
  *  to find associated FCCAs for this bridge
  *
  * @param widget An optional lambda to elaborate the host-land BridgeModule. See InMemoryBridgeAnnotation
  *
  * @param widgetClass The BridgeModule's full class name. See SerializableBridgeAnnotation
  *
  * @param widgetConstructorKey The BridgeModule's constructor argument.
  *
  */

private[midas] case class BridgeIOAnnotation(
    val target: ReferenceTarget,
    channelMapping: Map[String, String],
    widget: Option[(Parameters) => BridgeModule[_ <: TokenizedRecord]] = None,
    widgetClass: Option[String] = None,
    widgetConstructorKey: Option[_ <: AnyRef] = None) extends SingleTargetAnnotation[ReferenceTarget] {
  def duplicate(n: ReferenceTarget) = this.copy(target)
  def channelNames = channelMapping.map(_._2)

  // Elaborates the BridgeModule using the lambda if it exists
  // Otherwise, uses reflection to find the constructor for the class given by
  // widgetClass, passing it the widgetConstructorKey
  def elaborateWidget(implicit p: Parameters): BridgeModule[_ <: TokenizedRecord] = widget match {
    case Some(elaborator) => elaborator(p)
    case None =>
      println(s"Instantiating bridge ${target.ref} of type ${widgetClass.get}")
      val constructor = Class.forName(widgetClass.get).getConstructors()(0)
      (widgetConstructorKey match {
        case Some(key) =>
          println(s"  With constructor arguments: $key")
          constructor.newInstance(key, p)
        case None => constructor.newInstance(p)
      }).asInstanceOf[BridgeModule[_ <: TokenizedRecord]]
  }
}


private[midas] object BridgeIOAnnotation {
  // Useful when a pass emits these annotations directly; (they aren't promoted from BridgeAnnotation)
  def apply(target: ReferenceTarget,
            widget: (Parameters) => BridgeModule[_ <: TokenizedRecord],
            channelNames: Seq[String]): BridgeIOAnnotation =
   BridgeIOAnnotation(target, channelNames.map(p => p -> p).toMap, Some(widget))
}

