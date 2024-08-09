// See LICENSE for license details.

package midas
package widgets

import midas.core.{TargetChannelIO}

import org.chipsalliance.cde.config.{Parameters, Field}

import chisel3._
import chisel3.experimental.{BaseModule, ChiselAnnotation, annotate}

import scala.reflect.runtime.{universe => ru}

/* Bridge
 *
 * Bridges are widgets that operate directly on token streams moving to and
 * from the transformed-RTL model.
 *
 */

// Set in FPGA Top before the BridgeModule is generated
case object TargetClockInfo extends Field[Option[RationalClock]]

abstract class BridgeModule[HostPortType <: Record with HasChannels]()(implicit p: Parameters) extends Widget()(p) {
  def module: BridgeModuleImp[HostPortType]
}

abstract class BridgeModuleImp[HostPortType <: Record with HasChannels]
    (wrapper: BridgeModule[_ <: HostPortType])
    (implicit p: Parameters) extends WidgetImp(wrapper) {
  def hPort: HostPortType
  def clockDomainInfo: RationalClock = p(TargetClockInfo).get
}

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

trait HasChannels {
  /**
    * Returns a list of channel descriptors.
    */
  def bridgeChannels(): Seq[BridgeChannel]

  // Called in FPGATop to connect the instantiated bridge to channel ports on the wrapper
  private[midas] def connectChannels2Port(bridgeAnno: BridgeIOAnnotation, channels: TargetChannelIO): Unit
}
