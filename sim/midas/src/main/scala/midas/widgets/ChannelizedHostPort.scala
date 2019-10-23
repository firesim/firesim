// See LICENSE for license details.

package midas.widgets

import midas.core.SimWrapperChannels

import chisel3._
import chisel3.util._
import chisel3.experimental.{Direction}
import chisel3.experimental.DataMirror.directionOf

import scala.collection.mutable

abstract class ChannelizedHostPortIO(protected val targetPortProto: Data) extends TokenizedRecord {
  // Call in port definition to register a field as belonging to a unique channel
  def InputChannel[T <: Data](field: T) = channel(Direction.Input)(field)
  def OutputChannel[T <: Data](field: T) = channel(Direction.Output)(field)

  private val _inputWireChannels = mutable.ArrayBuffer[(Data, ReadyValidIO[Data])]()
  private val _outputWireChannels = mutable.ArrayBuffer[(Data, ReadyValidIO[Data])]()
  lazy val fieldToChannelMap = Map((_inputWireChannels ++ _outputWireChannels):_*)

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

  private def checkAllFieldsAssignedToChannels(): Unit = {
    def prefixWith(prefix: String, base: Any): String =
      if (prefix != "")  s"${prefix}.${base}" else base.toString

    def loop(name: String, field: Data): Seq[(String, Boolean)] = field match {
      case c: Clock => Seq(name -> true)
      case b: Record if fieldToChannelMap.isDefinedAt(b) => Seq(name -> true)
      case b: Record => b.elements.flatMap({ case (n, e) => loop(prefixWith(name, n), e) }).toSeq
      case v: Vec[_] if fieldToChannelMap.isDefinedAt(v) => Seq(name -> true)
      case v: Vec[_] => (v.zipWithIndex).flatMap({ case (e, i) => loop(s"${name}_$i", e) })
      case b: Bits => Seq(name -> fieldToChannelMap.isDefinedAt(b))
    }
    val messages = loop("", targetPortProto).collect({ case (name, assigned) if !assigned =>
      "Field ${name} of bridge IO is not assigned to a channel"})
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

  def generateAnnotations(): Unit = {
    generateWireChannelFCCAs(inputWireChannels, bridgeSunk = true)
    generateWireChannelFCCAs(outputWireChannels, bridgeSunk = false)
  }

  def connectChannels2Port(bridgeAnno: BridgeIOAnnotation, channels: SimWrapperChannels): Unit = {
    val local2globalName = bridgeAnno.channelMapping.toMap
    for (localName <- inputChannelNames) {
      elements(localName) <> channels.wireOutputPortMap(local2globalName(localName))
    }
    for (localName <- outputChannelNames) {
      channels.wireInputPortMap(local2globalName(localName)) <> elements(localName)
    }
  }
}
