// See LICENSE for license details.

package midas.widgets

import chisel3._

import org.chipsalliance.cde.config.Parameters

import firesim.lib.bridgeutils._

object BridgeIOAnnotationToElaboration {
  // Elaborates the BridgeModule using the lambda if it exists
  // Otherwise, uses reflection to find the constructor for the class given by
  // widgetClass, passing it the widgetConstructorKey
  def apply(anno: BridgeIOAnnotation)(implicit p: Parameters): BridgeModule[_ <: Record with HasChannels] = {
    println(s"Instantiating bridge ${anno.target.ref} of type ${anno.widgetClass}")

    val px          = p.alterPartial { case TargetClockInfo => anno.clockInfo }
    val constructor = Class.forName(anno.widgetClass).getConstructors()(0)
    (anno.widgetConstructorKey match {
      case Some(key) =>
        println(s"  With constructor arguments: $key")
        constructor.newInstance(key, px)
      case None      => constructor.newInstance(px)
    }).asInstanceOf[BridgeModule[_ <: Record with HasChannels]]
  }
}
