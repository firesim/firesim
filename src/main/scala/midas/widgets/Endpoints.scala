// See LICENSE for license details.

package midas
package widgets

import midas.core.{SimWrapperChannels, SimUtils}
import midas.core.SimUtils.{RVChTuple}

import midas.passes.fame.{FAMEChannelConnectionAnnotation,DecoupledForwardChannel, PipeChannel, DecoupledReverseChannel, WireChannel}

import freechips.rocketchip.config.Parameters

import chisel3._
import chisel3.util._
import chisel3.experimental.{BaseModule, Direction, ChiselAnnotation, annotate}
import chisel3.experimental.DataMirror.directionOf
import firrtl.annotations.{SingleTargetAnnotation} // Deprecated
import firrtl.annotations.{ReferenceTarget, ModuleTarget, AnnotationException}

import scala.collection.mutable
import scala.collection.immutable.ListMap

/* Endpoint
 *
 * Endpoints are widgets that operate directly on token streams moving to and
 * from the transformed-RTL model.
 *
 * Endpoints extend Widget to add an IO that includes a HostPort[T <: Data] which
 * contains bidriectional channels for token-flow moving from the transformed-RTL
 * model to the endpoint ("toHost"), and from the endpoint to the transformed
 * RTL model ("fromHost")
 *
 * Endpoints must also define a matcher-class that extends "trait Endpoint"
 * This guides MIDAS during platform-mapping, by matching on ports on the transformed-RTL 
 * whose Chisel-type matches the the Chisel-type of your endpoint's HostPort
 * and thus, which token streams, should be connected to your Endpoint. carried
 * by token
 */

abstract class EndpointWidget(implicit p: Parameters) extends Widget()(p) {
  def hPort: Record with HasChannels // Tokenized port moving between the endpoint the target-RTL
}

case class EndpointAnnotation(
  val target: ModuleTarget,
  widget: (Parameters) => EndpointWidget,
  channelNames: Seq[String]) extends SingleTargetAnnotation[ModuleTarget] {
  def duplicate(n: ModuleTarget) = this.copy(target)
  def toIOAnnotation(port: String): EndpointIOAnnotation = {
    val channelMapping = channelNames.map(oldName => oldName -> s"${port}_$oldName")
    EndpointIOAnnotation(target.copy(module = target.circuit).ref(port), widget, channelMapping.toMap)
   }
 }

private[midas] case class EndpointIOAnnotation(
    val target: ReferenceTarget,
    widget: (Parameters) => EndpointWidget,
    channelMapping: Map[String, String]) extends SingleTargetAnnotation[ReferenceTarget] {
  def duplicate(n: ReferenceTarget) = this.copy(target)
  def channelNames = channelMapping.map(_._2)
}


private[midas] object EndpointIOAnnotation {
  // Useful when a pass emits these annotations directly; (they aren't promoted from EndpointAnnotation)
  def apply(target: ReferenceTarget,
            widget: (Parameters) => EndpointWidget,
            channelNames: Seq[String]): EndpointIOAnnotation =
   EndpointIOAnnotation(target, widget, channelNames.map(p => p -> p).toMap)
}

trait IsEndpoint {
  self: BaseModule =>
  def endpointIO: HasChannels
  def widget: (Parameters) => EndpointWidget

  def generateAnnotations(): Unit = {
    // Generate the endpoint annotation
    annotate(new ChiselAnnotation { def toFirrtl =
      EndpointAnnotation(self.toNamed.toTarget, widget, endpointIO.allChannelNames)
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
