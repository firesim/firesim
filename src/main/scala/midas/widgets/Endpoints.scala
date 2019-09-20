// See LICENSE for license details.

package midas
package widgets

import midas.core.{SimWrapperChannels, SimUtils}
import midas.core.SimUtils.{RVChTuple}
import midas.passes.fame.{FAMEChannelConnectionAnnotation,DecoupledForwardChannel, PipeChannel, DecoupledReverseChannel, WireChannel, JsonProtocol, HasSerializationHints}

import freechips.rocketchip.config.Parameters

import chisel3._
import chisel3.util._
import chisel3.experimental.{BaseModule, Direction, ChiselAnnotation, annotate}
import firrtl.annotations.{ReferenceTarget}

import scala.reflect.runtime.{universe => ru}

/* Endpoint
 *
 * Endpoints are widgets that operate directly on token streams moving to and
 * from the transformed-RTL model.
 *
 */

abstract class TokenizedRecord extends Record with HasChannels

abstract class EndpointWidget[HostPortType <: TokenizedRecord]
  (implicit p: Parameters) extends Widget()(p) {
  def hPort: HostPortType
}

trait Endpoint[HPType <: TokenizedRecord, WidgetType <: EndpointWidget[HPType]] {
  self: BaseModule =>
  def constructorArg: Option[_ <: AnyRef]
  def endpointIO: HPType

  def generateAnnotations(): Unit = {

    // Adapted from https://medium.com/@giposse/scala-reflection-d835832ed13a
    val mirror = ru.runtimeMirror(getClass.getClassLoader)
    val classType = mirror.classSymbol(getClass)
    // The base class here is Endpoint, but it has not yet been parameterized. 
    val baseClassType = ru.typeOf[Endpoint[_,_]].typeSymbol.asClass
    // Now this will be the type-parameterized form of Endpoint
    val baseType = ru.internal.thisType(classType).baseType(baseClassType)
    val widgetClassSymbol = baseType.typeArgs(1).typeSymbol.asClass

    // Generate the endpoint annotation
    annotate(new ChiselAnnotation { def toFirrtl = {
        SerializableEndpointAnnotation(
          self.toNamed.toTarget,
          endpointIO.allChannelNames,
          widgetClass = widgetClassSymbol.fullName,
          widgetConstructorKey = constructorArg)
      }
    })
    // Emit annotations to capture channel information
    endpointIO.generateAnnotations()
  }
}

trait HasChannels {
  // A template of the target-land port that is channelized in this host-land port
  protected def targetPortProto: Data

  // Conventional channels corresponding to a single, unidirectioned token stream
  def outputWireChannels: Seq[(Data, String)]
  def inputWireChannels: Seq[(Data, String)]

  // Ready-valid channels with bidirectional token streams.
  // Used to emit special channel annotations to generate MIDAS I-like
  // simulators that run with FMR=1
  def outputRVChannels: Seq[RVChTuple]
  def inputRVChannels: Seq[RVChTuple]

  // Called to emit FCCAs in the target RTL in order to assign the target
  // port's fields to channels
  def generateAnnotations(): Unit

  // Called in FPGATop to connect the instantiated endpoint to channel ports on the wrapper
  private[midas] def connectChannels2Port(endpointAnno: EndpointIOAnnotation, channels: SimWrapperChannels): Unit

  /*
   * Implementation follows
   *
   */

  private[midas] def inputChannelNames(): Seq[String] = inputWireChannels.map(_._2)
  private[midas] def outputChannelNames(): Seq[String] = outputWireChannels.map(_._2)

  private def getRVChannelNames(channels: Seq[RVChTuple]): Seq[String] =
    channels.flatMap({ channel =>
      val (fwd, rev) =  SimUtils.rvChannelNamePair(channel)
      Seq(fwd, rev)
    })

  private[midas] def outputRVChannelNames = getRVChannelNames(outputRVChannels)
  private[midas] def inputRVChannelNames = getRVChannelNames(inputRVChannels)

  private[midas] def allChannelNames(): Seq[String] = inputChannelNames ++ outputChannelNames ++
    outputRVChannelNames ++ inputRVChannelNames

  // FCCA renamer can't handle flattening of an aggregate target; so do it manually
  protected def lowerAggregateIntoLeafTargets(bits: Data): Seq[ReferenceTarget] = {
    val (ins, outs, _, _) = SimUtils.parsePorts(bits)
    require (ins.isEmpty || outs.isEmpty, "Aggregate should be uni-directional")
    (ins ++ outs).map({ case (leafField, _) => leafField.toNamed.toTarget })
  }

  // Create a wire channel annotation
  protected def generateWireChannelFCCAs(channels: Seq[(Data, String)], endpointSunk: Boolean = false, latency: Int = 0): Unit = {
    for ((field, chName) <- channels) {
      annotate(new ChiselAnnotation { def toFirrtl =
        if (endpointSunk) {
          FAMEChannelConnectionAnnotation.source(chName, PipeChannel(latency), Seq(field.toNamed.toTarget))
        } else {
          FAMEChannelConnectionAnnotation.sink  (chName, PipeChannel(latency), Seq(field.toNamed.toTarget))
        }
      })
    }
  }

  // Create Ready Valid channel annotations assuming endpoint-sourced directions
  protected def generateRVChannelFCCAs(channels: Seq[(ReadyValidIO[Data], String)], endpointSunk: Boolean = false): Unit = {
    for ((field, chName) <- channels) yield {
      // Generate the forward channel annotation
      val (fwdChName, revChName)  = SimUtils.rvChannelNamePair(chName)
      annotate(new ChiselAnnotation { def toFirrtl = {
        val validTarget = field.valid.toNamed.toTarget
        val readyTarget = field.ready.toNamed.toTarget
        val leafTargets = Seq(validTarget) ++ lowerAggregateIntoLeafTargets(field.bits)
        // Endpoint is the sink; it applies target backpressure
        if (endpointSunk) {
          FAMEChannelConnectionAnnotation.source(
            fwdChName,
            DecoupledForwardChannel.source(validTarget, readyTarget),
            leafTargets
          )
        } else {
        // Endpoint is the source; it asserts target-valid and recieves target-backpressure
          FAMEChannelConnectionAnnotation.sink(
            fwdChName,
            DecoupledForwardChannel.sink(validTarget, readyTarget),
            leafTargets
          )
        }
      }})

      annotate(new ChiselAnnotation { def toFirrtl = {
        val readyTarget = Seq(field.ready.toNamed.toTarget)
        if (endpointSunk) {
          FAMEChannelConnectionAnnotation.sink(revChName, DecoupledReverseChannel, readyTarget)
        } else {
          FAMEChannelConnectionAnnotation.source(revChName, DecoupledReverseChannel, readyTarget)
        }
      }})
    }
  }
}
