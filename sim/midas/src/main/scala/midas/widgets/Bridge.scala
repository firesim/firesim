// See LICENSE for license details.

package midas
package widgets

import midas.core.{SimWrapperChannels, SimUtils}
import midas.core.SimUtils.{RVChTuple}
import midas.passes.fame.{FAMEChannelConnectionAnnotation,DecoupledForwardChannel, PipeChannel, DecoupledReverseChannel, WireChannel}

import freechips.rocketchip.config.{Parameters, Field}

import chisel3._
import chisel3.util._
import chisel3.experimental.{BaseModule, Direction, ChiselAnnotation, annotate}
import firrtl.annotations.{ReferenceTarget, JsonProtocol, HasSerializationHints}

import scala.reflect.runtime.{universe => ru}

/* Bridge
 *
 * Bridges are widgets that operate directly on token streams moving to and
 * from the transformed-RTL model.
 *
 */

// Set in FPGA Top before the BridgeModule is generated
case object TargetClockInfo extends Field[Option[RationalClock]]

abstract class TokenizedRecord extends Record with HasChannels

abstract class BridgeModule[HostPortType <: Record with HasChannels]()(implicit p: Parameters) extends Widget()(p) {
  def module: BridgeModuleImp[HostPortType]
}

abstract class BridgeModuleImp[HostPortType <: Record with HasChannels]
    (wrapper: BridgeModule[_ <: HostPortType])
    (implicit p: Parameters) extends WidgetImp(wrapper) {
  def hPort: HostPortType
  def clockDomainInfo: RationalClock = p(TargetClockInfo).getOrElse(RationalClock("workaround", 1, 1))
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

    // Emit annotations to capture channel information
    bridgeIO.generateAnnotations()
    // Generate the bridge annotation
    annotate(new ChiselAnnotation { def toFirrtl = {
        SerializableBridgeAnnotation(
          self.toNamed.toTarget,
          bridgeIO.allChannelNames,
          widgetClass = widgetClassSymbol.fullName,
          widgetConstructorKey = constructorArg)
      }
    })
  }
}

trait HasChannels {
  // A template of the target-land port that is channelized in this host-land port
  protected def targetPortProto: Data

  /**
    *  Called to emit FCCAs in the target RTL in order to assign the target
    *  port's fields to channels.
    */
  def generateAnnotations(): Unit

  /**
    * Returns a list of channel names for which FAMECHannelConnectionAnnotations have been generated
    */
  def allChannelNames(): Seq[String]

  // Called in FPGATop to connect the instantiated bridge to channel ports on the wrapper
  private[midas] def connectChannels2Port(bridgeAnno: BridgeIOAnnotation, channels: SimWrapperChannels): Unit

  /*
   * Implementation follows
   *
   */
  private[midas] def getClock(): Clock = {
    val allTargetClocks = SimUtils.findClocks(targetPortProto)
    require(allTargetClocks.nonEmpty,
      s"Target-side bridge interface of ${targetPortProto.getClass} has no clock field.")
    require(allTargetClocks.size == 1,
      s"Target-side bridge interface of ${targetPortProto.getClass} has ${allTargetClocks.size} clocks but must define only one.")
    allTargetClocks.head
  }

  private def getRVChannelNames(channels: Seq[RVChTuple]): Seq[String] =
    channels.flatMap({ channel =>
      val (fwd, rev) =  SimUtils.rvChannelNamePair(channel)
      Seq(fwd, rev)
    })

  // Create a wire channel annotation
  protected def generateWireChannelFCCAs(channels: Seq[(Data, String)], bridgeSunk: Boolean = false, latency: Int = 0): Unit = {
    for ((field, chName) <- channels) {
      annotate(new ChiselAnnotation { def toFirrtl =
        if (bridgeSunk) {
          FAMEChannelConnectionAnnotation.source(chName, PipeChannel(latency), Some(getClock.toNamed.toTarget), Seq(field.toNamed.toTarget))
        } else {
          FAMEChannelConnectionAnnotation.sink(chName, PipeChannel(latency), Some(getClock.toNamed.toTarget), Seq(field.toNamed.toTarget))
        }
      })
    }
  }

  // Create Ready Valid channel annotations assuming bridge-sourced directions
  protected def generateRVChannelFCCAs(channels: Seq[(ReadyValidIO[Data], String)], bridgeSunk: Boolean = false): Unit = {
    for ((field, chName) <- channels) yield {
      // Generate the forward channel annotation
      val (fwdChName, revChName)  = SimUtils.rvChannelNamePair(chName)
      annotate(new ChiselAnnotation { def toFirrtl = {
        val clockTarget = Some(getClock.toNamed.toTarget)
        val validTarget = field.valid.toNamed.toTarget
        val readyTarget = field.ready.toNamed.toTarget
        val leafTargets = Seq(validTarget) ++ SimUtils.lowerAggregateIntoLeafTargets(field.bits)
        // Bridge is the sink; it applies target backpressure
        if (bridgeSunk) {
          FAMEChannelConnectionAnnotation.source(
            fwdChName,
            DecoupledForwardChannel.source(validTarget, readyTarget),
            clockTarget,
            leafTargets
          )
        } else {
        // Bridge is the source; it asserts target-valid and recieves target-backpressure
          FAMEChannelConnectionAnnotation.sink(
            fwdChName,
            DecoupledForwardChannel.sink(validTarget, readyTarget),
            clockTarget,
            leafTargets
          )
        }
      }})

      annotate(new ChiselAnnotation { def toFirrtl = {
        val clockTarget = Some(getClock.toNamed.toTarget)
        val readyTarget = Seq(field.ready.toNamed.toTarget)
        if (bridgeSunk) {
          FAMEChannelConnectionAnnotation.sink(revChName, DecoupledReverseChannel, clockTarget, readyTarget)
        } else {
          FAMEChannelConnectionAnnotation.source(revChName, DecoupledReverseChannel, clockTarget, readyTarget)
        }
      }})
    }
  }
}
