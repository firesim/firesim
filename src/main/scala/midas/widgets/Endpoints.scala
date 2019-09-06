// See LICENSE for license details.

package midas
package widgets

import midas.core.{IsRationalClockRatio, UnityClockRatio, HostPort, HostPortIO, SimUtils}
import midas.core.SimUtils.{RVChTuple}

import midas.passes.fame.{FAMEChannelConnectionAnnotation,DecoupledForwardChannel, PipeChannel, DecoupledReverseChannel, WireChannel}

import freechips.rocketchip.config.Parameters

import chisel3._
import chisel3.util._
import chisel3.experimental.{Direction, ChiselAnnotation, annotate}
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
  def hPort: Record with HasEndpointChannels // Tokenized port moving between the endpoint the target-RTL
}


trait Endpoint {
  protected val channels = mutable.ArrayBuffer[(String, Record)]()
  protected val wires = mutable.HashSet[Bits]()
  def clockRatio: IsRationalClockRatio = UnityClockRatio
  def matchType(data: Data): Boolean
  def widget(p: Parameters): EndpointWidget
  def widgetName: String = getClass.getSimpleName
  final def size = channels.size
  final def apply(wire: Bits) = wires(wire)
  final def apply(i: Int) = channels(i)
  def add(name: String, channel: Data) {
    val (ins, outs, _, _) = SimUtils.parsePorts(channel)
    wires ++= (ins ++ outs).unzip._1
    channels += (name -> channel.asInstanceOf[Record])
  }

  // Finds all of the target ReadyValid bundles sourced or sunk by the target
  // input => sunk by the target
  private def findRVChannels(dir: Direction): Seq[(String, ReadyValidIO[Data])] =
    channels.flatMap({ case (prefix, data) => data.elements.toSeq.collect({
        case (name, rv: ReadyValidIO[_]) if directionOf(rv.valid) == dir => s"${prefix}_${name}" -> rv
      })
  })

  lazy val readyValidOutputs = findRVChannels(Direction.Output)
  lazy val readyValidInputs = findRVChannels(Direction.Input)
}
// MIDAS 2.0
//trait Endpoint {
//  val channels = ArrayBuffer[(String, Record)]()
//  val wires = HashSet[Element]()
//  def clockRatio: IsRationalClockRatio = UnityClockRatio
//  def matchType(data: Data): Boolean
//  def widget(p: Parameters): EndpointWidget
//  def widgetName: String = getClass.getSimpleName
//  final def size = channels.size
//  final def apply(wire: Element) = wires(wire)
//  final def apply(i: Int) = channels(i)
//  def add(name: String, channel: Data) {
//    val (ins, outs, _, _) = SimUtils.parsePorts(channel)
//    wires ++= (ins ++ outs).unzip._1
//    channels += (name -> channel.asInstanceOf[Record])

case class EndpointMap(endpoints: Seq[Endpoint]) {
  def get(data: Data) = endpoints find (_ matchType data)
  def ++(x: EndpointMap) = EndpointMap(endpoints ++ x.endpoints)
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
  self: BlackBox =>
  def endpointIO: HasEndpointChannels
  def widget: (Parameters) => EndpointWidget

  // FCCA renamer can't handle flattening of an aggregate target; so do it manually
  private def lowerAggregateIntoLeafTargets(bits: Data): Seq[ReferenceTarget] = {
    val (ins, outs, _, _) = SimUtils.parsePorts(bits)
    require (ins.isEmpty || outs.isEmpty, "Aggregate should be uni-directional")
    (ins ++ outs).map({ case (leafField, _) => leafField.toNamed.toTarget })
  }

  // Create a wire channel annotation
  private def generateWireChannelFCCAs(channels: Seq[(Data, String)], endpointSunk: Boolean = false): Unit = {
    for ((field, chName) <- channels) {
      annotate(new ChiselAnnotation { def toFirrtl =
        if (endpointSunk) {
          FAMEChannelConnectionAnnotation.source(chName, WireChannel, Seq(field.toNamed.toTarget))
        } else {
          FAMEChannelConnectionAnnotation.sink  (chName, WireChannel, Seq(field.toNamed.toTarget))
        }
      })
    }
  }

  // Create Ready Valid channel annotations assuming endpoint-sourced directions
  private def generateRVChannelFCCAs(channels: Seq[(ReadyValidIO[Data], String)], endpointSunk: Boolean = false): Unit = {
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

  def generateAnnotations(): Unit = {

   // Generate the endpoint annotation
   annotate(new ChiselAnnotation { def toFirrtl =
     EndpointAnnotation(self.toNamed.toTarget, widget, endpointIO.allChannelNames)
   })

    generateWireChannelFCCAs(endpointIO.outputWireChannels)
    generateWireChannelFCCAs(endpointIO.inputWireChannels, endpointSunk = true)
    generateRVChannelFCCAs(endpointIO.outputRVChannels)
    generateRVChannelFCCAs(endpointIO.inputRVChannels, endpointSunk = true)
  }
}

trait HasEndpointChannels {
  def outputWireChannels: Seq[(Data, String)]
  def inputWireChannels: Seq[(Data, String)]
  def inputChannelNames(): Seq[String] = inputWireChannels.map(_._2)
  def outputChannelNames(): Seq[String] = outputWireChannels.map(_._2)


  // Specially labelled ready-valid channels. Used to emit special channel
  // annotations to generate MIDAS I-like simulators that run with FMR=1
  def outputRVChannels: Seq[RVChTuple]
  def inputRVChannels: Seq[RVChTuple]

  private def getRVChannelNames(channels: Seq[RVChTuple]): Seq[String] =
    channels.flatMap({ channel =>
      val (fwd, rev) =  SimUtils.rvChannelNamePair(channel)
      Seq(fwd, rev)
    })

  def outputRVChannelNames = getRVChannelNames(outputRVChannels)
  def inputRVChannelNames = getRVChannelNames(inputRVChannels)

  def allChannelNames(): Seq[String] = inputChannelNames ++ outputChannelNames ++
    outputRVChannelNames ++ inputRVChannelNames

}

abstract class ChannelizedHostPortIO(private val gen: Data) extends Record with HasEndpointChannels {
  private val _inputWireChannels = mutable.ArrayBuffer[(Data, ReadyValidIO[Data])]()
  private val _outputWireChannels = mutable.ArrayBuffer[(Data, ReadyValidIO[Data])]()
  lazy val channelMapping = Map((_inputWireChannels ++ _outputWireChannels):_*)

  private def getLeafDirs(token: Data): Seq[Direction] = token match {
    case c: Clock => Seq()
    case b: Record => b.elements.flatMap({ case (_, e) => getLeafDirs(e)}).toSeq
    case v: Vec[_] => v.flatMap(getLeafDirs)
    case b: Bits => Seq(directionOf(b))
  }
  // Simplifying assumption: if the user wants to aggregate a bunch of wires
  // into a single channel they should aggregate them into a Bundle on their record
  private def channel[T <: Data](direction: Direction)(field: T): DecoupledIO[T] = {
    val directions = getLeafDirs(field)
    val isUnidirectional = directions.zip(directions.tail).map({ case (a, b) => a == b })
                                                          .foldLeft(true)(_ && _)
    require(isUnidirectional, "Token channels must have a unidirectioned payload")
    require(directions.head == direction)

    directions.head match {
      case Direction.Input => {
        val ch = Flipped(Decoupled(field.cloneType))
        _inputWireChannels += (field -> ch)
        ch
      }
      case Direction.Output => {
        val ch = Decoupled(field.cloneType)
        _outputWireChannels += (field -> ch)
        ch
      }
    }
  }

  def InputChannel[T <: Data](field: T) = channel(Direction.Input)(field)
  def OutputChannel[T <: Data](field: T) = channel(Direction.Output)(field)

  //def InputRVChannel[T <: Data](field: T) = channel(Direction.Input)(field)
  //def OutputRVChannel[T <: Data](field: T) = channel(Direction.Output)(field)

  private def checkAllFieldsAssignedToChannels(): Unit = {
    def prefixWith(prefix: String, base: Any): String =
      if (prefix != "")  s"${prefix}.${base}" else base.toString

    def loop(name: String, field: Data): Seq[(String, Boolean)] = field match {
      case c: Clock => Seq(name -> true)
      case b: Record if channelMapping.isDefinedAt(b) => Seq(name -> true)
      case b: Record => b.elements.flatMap({ case (n, e) => loop(prefixWith(name, n), e) }).toSeq
      case v: Vec[_] if channelMapping.isDefinedAt(v) => Seq(name -> true)
      case v: Vec[_] => (v.zipWithIndex).flatMap({ case (e, i) => loop(s"${name}_$i", e) })
      case b: Bits => Seq(name -> channelMapping.isDefinedAt(b))
    }
    val messages = loop("", gen).collect({ case (name, assigned) if !assigned =>
      "Field ${name} of endpoint IO is not assigned to a channel"})
    assert(messages.isEmpty, messages.mkString("\n"))
  }

  def inputWireChannels: Seq[(Data, String)] = {
    checkAllFieldsAssignedToChannels()
    val reverseElementMap = elements.map({ case (name, field) => field -> name  }).toMap
    _inputWireChannels.map({ case (tField, channel) => (tField, reverseElementMap(channel)) })  
  }

  def outputWireChannels: Seq[(Data, String)] = {
    checkAllFieldsAssignedToChannels()
    val reverseElementMap = elements.map({ case (name, field) => field -> name  }).toMap
    _outputWireChannels.map({ case (tField, channel) => (tField, reverseElementMap(channel)) })  
  }

  def inputRVChannels = Seq.empty
  def outputRVChannels = Seq.empty
}
