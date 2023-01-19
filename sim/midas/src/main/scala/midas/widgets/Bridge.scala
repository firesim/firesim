// See LICENSE for license details.

package midas
package widgets

import midas.core.{TargetChannelIO}

import freechips.rocketchip.config.{Parameters, Field}

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
  def emitClockDomainInfo(headerWidgetName: String, sb: StringBuilder): Unit = {
    import CppGenerationUtils._
    val RationalClock(domainName, mul, div) = clockDomainInfo
    sb.append(genStatic(s"${headerWidgetName}_clock_domain_name", CStrLit(domainName)))
    sb.append(genConstStatic(s"${headerWidgetName}_clock_multiplier", UInt32(mul)))
    sb.append(genConstStatic(s"${headerWidgetName}_clock_divisor", UInt32(div)))
  }

}

trait Bridge[HPType <: Record with HasChannels, WidgetType <: BridgeModule[HPType]] {
  self: BaseModule =>
  def constructorArg: Option[_ <: AnyRef]
  def bridgeIO: HPType

  def generateAnnotations(): Unit = {

    // Adapted from https://medium.com/@giposse/scala-reflection-d835832ed13a
    val mirror = ru.runtimeMirror(getClass.getClassLoader)
    val classType = mirror.classSymbol(getClass)
    // The base class here is Bridge, but it has not yet been parameterized.
    val baseClassType = ru.typeOf[Bridge[_,_]].typeSymbol.asClass
    // Now this will be the type-parameterized form of Bridge
    val baseType = ru.internal.thisType(classType).baseType(baseClassType)
    val widgetClassSymbol = baseType.typeArgs(1).typeSymbol.asClass

    // Generate the bridge annotation
    annotate(new ChiselAnnotation { def toFirrtl = {
        BridgeAnnotation(
          self.toNamed.toTarget,
          bridgeIO.bridgeChannels(),
          widgetClass = widgetClassSymbol.fullName,
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
