// See LICENSE for license details.

package midas.widgets

import chisel3._

import firrtl.{RenameMap}
import firrtl.annotations.{SingleTargetAnnotation} // Deprecated
import firrtl.annotations.{Annotation, ReferenceTarget, ModuleTarget, HasSerializationHints}
import freechips.rocketchip.config.Parameters

import midas.passes.fame.{RTRenamer}
import midas.targetutils.FAMEAnnotation

sealed trait BridgeChannel {
  def update(renames: RenameMap): BridgeChannel
}

/**
  * Descriptor for a pipe channel ending at the bridge.
  */
case class PipeBridgeChannel(
    name: String,
    clock: ReferenceTarget,
    sinks: Seq[ReferenceTarget],
    sources: Seq[ReferenceTarget],
    latency: Int
) extends BridgeChannel {
  def update(renames: RenameMap): BridgeChannel = {
    val renamer = RTRenamer.exact(renames)
    PipeBridgeChannel(name, renamer(clock), sinks.map(renamer), sources.map(renamer), latency)
  }
}

/**
  * Descriptor for a clock channel originating from a clock bridge.
  */
case class ClockBridgeChannel(
    name: String,
    sinks: Seq[ReferenceTarget],
    clocks: Seq[RationalClock],
    clockMFMRs: Seq[Int]
) extends BridgeChannel {
  def update(renames: RenameMap): BridgeChannel = {
    val renamer = RTRenamer.exact(renames)
    ClockBridgeChannel(name, sinks.map(renamer), clocks, clockMFMRs)
  }
}

/**
  * Descriptor for a Ready-Valid channel originating from a bridge.
  */
case class ReadyValidBridgeChannel(
    fwdName: String,
    revName: String,
    clock: ReferenceTarget,
    sinks: Seq[ReferenceTarget],
    sources: Seq[ReferenceTarget],
    valid: ReferenceTarget,
    ready: ReferenceTarget,
) extends BridgeChannel {
  def update(renames: RenameMap): BridgeChannel = {
    val renamer = RTRenamer.exact(renames)
    ReadyValidBridgeChannel(
      fwdName,
      revName,
      renamer(clock),
      sinks.map(renamer),
      sources.map(renamer),
      renamer(valid),
      renamer(ready)
    )
  }
}

/**
  * A serializable annotation emitted by Chisel Modules that extend Bridge
  *
  * @param target  The module representing an Bridge. Typically a black box
  *
  * @param bridgeChannels  A list of descriptors for the channels attached to the
  *   bridge. FCCAs are materialized from these descriptors.
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

case class BridgeAnnotation(
    target: ModuleTarget,
    bridgeChannels: Seq[BridgeChannel],
    widgetClass: String,
    widgetConstructorKey: Option[_ <: AnyRef])
  extends SingleTargetAnnotation[ModuleTarget] with FAMEAnnotation with HasSerializationHints {

  /**
    * Invoked by BridgeExtraction to convert this ModuleTarget-based annotation into
    * a ReferenceTarget based one that can be attached to newly created IO on the top-level
    */
  def toIOAnnotation(port: String): BridgeIOAnnotation = {
    val channelNames = bridgeChannels.flatMap({
      case ch: PipeBridgeChannel => Seq(ch.name)
      case ch: ClockBridgeChannel => Seq(ch.name)
      case ch: ReadyValidBridgeChannel => Seq(ch.fwdName, ch.revName)
    })
    val channelMapping = channelNames.map(oldName => oldName -> s"${port}_$oldName")
    BridgeIOAnnotation(target.copy(module = target.circuit).ref(port),
      channelMapping.toMap,
      widgetClass = widgetClass,
      widgetConstructorKey = widgetConstructorKey
    )
  }

  def typeHints = bridgeChannels.map(_.getClass) ++ (widgetConstructorKey match {
    // If the key has extra type hints too, grab them as well
    case Some(key: HasSerializationHints) => key.getClass +: key.typeHints
    case Some(key) => Seq(key.getClass)
    case None => Seq()
  })

  def duplicate(n: ModuleTarget) = this.copy(target)

  override def update(renames: RenameMap): Seq[Annotation] = {
    val renamer = RTRenamer.exact(renames)
    Seq(BridgeAnnotation(target, bridgeChannels.map(_.update(renames)), widgetClass, widgetConstructorKey))
  }
}

/**
  * An BridgeAnnotation that references the IO created by BridgeExtraction after it has promoted and removed
  * all modules annotated with BridgeAnnotations.
  *
  * @param target  The IO corresponding to and Bridge's interface
  *
  * @param channelMapping A mapping from the channel names initially emitted by the Chisel Module, to uniquified global ones
  *  to find associated FCCAs for this bridge
  *
  * @param clockInfo Contains information about the domain in which the bridge is instantiated. 
  *  This will always be nonEmpty for bridges instantiated in the input FIRRTL
  *
  * @param widgetClass The BridgeModule's full class name. See BridgeAnnotation
  *
  * @param widgetConstructorKey The BridgeModule's constructor argument.
  *
  */

private[midas] case class BridgeIOAnnotation(
    target: ReferenceTarget,
    channelMapping: Map[String, String],
    clockInfo: Option[RationalClock] = None,
    widgetClass: String,
    widgetConstructorKey: Option[_ <: AnyRef] = None)
    extends SingleTargetAnnotation[ReferenceTarget] with FAMEAnnotation with HasSerializationHints {

  def typeHints = widgetConstructorKey match {
    // If the key has extra type hints too, grab them as well
    case Some(key: HasSerializationHints) => key.getClass +: key.typeHints
    case Some(key) => Seq(key.getClass)
    case None => Seq()
  }
  def duplicate(n: ReferenceTarget) = this.copy(target)
  def channelNames = channelMapping.map(_._2)

  // Elaborates the BridgeModule using the lambda if it exists
  // Otherwise, uses reflection to find the constructor for the class given by
  // widgetClass, passing it the widgetConstructorKey
  def elaborateWidget(implicit p: Parameters): BridgeModule[_ <: Record with HasChannels] = {
    println(s"Instantiating bridge ${target.ref} of type ${widgetClass}")

    val px = p alterPartial { case TargetClockInfo => clockInfo }
    val constructor = Class.forName(widgetClass).getConstructors()(0)
    (widgetConstructorKey match {
      case Some(key) =>
        println(s"  With constructor arguments: $key")
        constructor.newInstance(key, px)
      case None => constructor.newInstance(px)
    }).asInstanceOf[BridgeModule[_ <: Record with HasChannels]]
  }
}

private[midas] object BridgeIOAnnotation {
  // Useful when a pass emits these annotations directly; (they aren't promoted from BridgeAnnotation)
  def apply(target: ReferenceTarget,
            channelNames: Seq[String],
            widgetClass: String,
            widgetConstructorKey: AnyRef): BridgeIOAnnotation =
   BridgeIOAnnotation(
      target,
      channelNames.map(p => p -> p).toMap,
      widgetClass = widgetClass,
      widgetConstructorKey = Some(widgetConstructorKey))
}
