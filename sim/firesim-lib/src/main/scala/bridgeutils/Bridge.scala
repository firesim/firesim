// See LICENSE for license details.

package firesim.lib.bridgeutils

import chisel3.Record
import chisel3.experimental.{annotate, BaseModule, ChiselAnnotation}

/* Bridge
 *
 * Bridges are widgets that operate directly on token streams moving to and
 * from the transformed-RTL model.
 *
 */
trait Bridge[HostPortType <: Record with HasChannels] {
  self: BaseModule =>
  def constructorArg: Option[_ <: AnyRef]
  def bridgeIO:       HostPortType
  def moduleName:     String

  def generateAnnotations(): Unit = {
    // Generate the bridge annotation
    annotate(new ChiselAnnotation {
      def toFirrtl = {
        BridgeAnnotation(
          self.toNamed.toTarget,
          bridgeIO.bridgeChannels(),
          widgetClass          = moduleName,
          widgetConstructorKey = constructorArg,
        )
      }
    })
  }
}

trait HasChannels {

  /** Returns a list of channel descriptors.
    */
  def bridgeChannels(): Seq[BridgeChannel]
}
