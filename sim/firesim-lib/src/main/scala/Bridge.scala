// See LICENSE for license details.

package firesim.lib

import chisel3.experimental.{BaseModule, ChiselAnnotation, annotate}
import firrtl.{RenameMap}
import firrtl.annotations.{SingleTargetAnnotation} // Deprecated
import firrtl.annotations.{Annotation, ReferenceTarget, ModuleTarget, HasSerializationHints}
import midas.targetutils.FAMEAnnotation

/**
  * A serializable annotation emitted by Chisel Modules that extend Bridge
  *
  * @param target  The module representing an Bridge. Typically a black box
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
    widgetClass: String,
    widgetConstructorKey: Option[_ <: AnyRef])
  extends SingleTargetAnnotation[ModuleTarget] with FAMEAnnotation with HasSerializationHints {

  /**
    * Invoked by BridgeExtraction to convert this ModuleTarget-based annotation into
    * a ReferenceTarget based one that can be attached to newly created IO on the top-level
    */
  def toIOAnnotation(port: String): BridgeIOAnnotation = {
    BridgeIOAnnotation(target.copy(module = target.circuit).ref(port),
      widgetClass = widgetClass,
      widgetConstructorKey = widgetConstructorKey
    )
  }

  def typeHints = (widgetConstructorKey match {
    // Since midas only traverse down 1 layer searching for typeHints,
    // the BridgeKey must provide a recursive definition of typeHints if non-serializable objects are embedded deeper
    case Some(key: HasSerializationHints) => key.getClass +: key.typeHints
    case Some(key) => Seq(key.getClass)
    case None => Seq()
  })

  def duplicate(n: ModuleTarget) = this.copy(target)

  override def update(renames: RenameMap): Seq[Annotation] = {
    Seq(BridgeAnnotation(target, widgetClass, widgetConstructorKey))
  }
}

/**
  * An BridgeAnnotation that references the IO created by BridgeExtraction after it has promoted and removed
  * all modules annotated with BridgeAnnotations.
  *
  * @param target  The IO corresponding to and Bridge's interface
  *
  * @param clockInfo Contains information about the domain in which the bridge is instantiated.
  *  This will always be nonEmpty for bridges instantiated in the input FIRRTL
  *
  * @param widgetClass The BridgeModule's full class name. See BridgeAnnotation
  *
  * @param widgetConstructorKey The BridgeModule's constructor argument.
  *
  */

case class BridgeIOAnnotation(
    target: ReferenceTarget,
    //clockInfo: Option[RationalClock] = None,
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

  // TODO: Done separately
  //// Elaborates the BridgeModule using the lambda if it exists
  //// Otherwise, uses reflection to find the constructor for the class given by
  //// widgetClass, passing it the widgetConstructorKey
  //def elaborateWidget(implicit p: Parameters): BridgeModule[_ <: Record with HasChannels] = {
  //  println(s"Instantiating bridge ${target.ref} of type ${widgetClass}")

  //  val px = p alterPartial { case TargetClockInfo => clockInfo }
  //  val constructor = Class.forName(widgetClass).getConstructors()(0)
  //  (widgetConstructorKey match {
  //    case Some(key) =>
  //      println(s"  With constructor arguments: $key")
  //      constructor.newInstance(key, px)
  //    case None => constructor.newInstance(px)
  //  }).asInstanceOf[BridgeModule[_ <: Record with HasChannels]]
  //}
}

object BridgeIOAnnotation {
  // Useful when a pass emits these annotations directly; (they aren't promoted from BridgeAnnotation)
  def apply(target: ReferenceTarget,
            widgetClass: String,
            widgetConstructorKey: AnyRef): BridgeIOAnnotation =
   BridgeIOAnnotation(
      target,
      widgetClass = widgetClass,
      widgetConstructorKey = Some(widgetConstructorKey))
}

/* Bridge
 *
 * Bridges are widgets that operate directly on token streams moving to and
 * from the transformed-RTL model.
 *
 */
trait Bridge {
  self: BaseModule =>
  def constructorArg: Option[_ <: AnyRef]
  def moduleName: String

  def generateAnnotations(): Unit = {
    // Generate the bridge annotation
    annotate(new ChiselAnnotation { def toFirrtl = {
        BridgeAnnotation(
          self.toNamed.toTarget,
          widgetClass = moduleName,
          widgetConstructorKey = constructorArg)
      }
    })
  }
}
