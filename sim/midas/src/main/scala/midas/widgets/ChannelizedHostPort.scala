// See LICENSE for license details.

package midas.widgets

import midas.core.SimWrapperChannels

import midas.passes.fame.{FAMEChannelInfo, FAMEChannelConnectionAnnotation}

import chisel3._
import chisel3.util._
import chisel3.experimental.{Direction, ChiselAnnotation, annotate}
import chisel3.experimental.DataMirror.directionOf

import firrtl.annotations.ReferenceTarget

import scala.collection.mutable

trait ChannelMetadata {
  def clockRT(): Option[ReferenceTarget]
  def chInfo(): FAMEChannelInfo
  def fieldRTs(): Seq[ReferenceTarget]
  def bridgeSunk: Boolean
  // Channel name is passed as an argument here because it is determined reflexively after the record
  // is constructed -- it's not avaiable when the metadata instance is created
  def generateAnnotations(chName: String): Seq[ChiselAnnotation] = {
    Seq(new ChiselAnnotation { def toFirrtl =
      if (bridgeSunk) {
        FAMEChannelConnectionAnnotation.source(chName, chInfo, clockRT, fieldRTs)
      } else {
        FAMEChannelConnectionAnnotation.sink(chName, chInfo, clockRT, fieldRTs)
      }
    })
  }
}

case class PipeChannelMetadata(field: Data, clock: () => Clock, bridgeSunk: Boolean, latency: Int = 0) extends ChannelMetadata {
  def fieldRTs = Seq(field.toTarget)
  def clockRT = Some(clock().toTarget)
  def chInfo = midas.passes.fame.PipeChannel(latency)
}

case class ClockChannelMetadata(field: Data, bridgeSunk: Boolean) extends ChannelMetadata {
  def fieldRTs = field match {
    case c: Clock => Seq(c.toTarget)
    case v: Vec[Clock] => v.map(_.toTarget)
    case o => ???
  }
  def clockRT = None
  def chInfo = midas.passes.fame.TargetClockChannel(Seq.tabulate(fieldRTs.length)(i => RationalClock(s"clock_$i",1, 1)))
}

case class ClockControlChannelMetadata(field: Data, bridgeSunk: Boolean) extends ChannelMetadata {
  def fieldRTs = Seq(field.toTarget)
  def clockRT = None
  def chInfo = midas.passes.fame.ClockControlChannel
}

trait IndependentChannels extends HasChannels { this: Record =>
  type ChannelType[A <: Data] <: Data
  def payloadWrapper[A <: Data](payload: A): ChannelType[A]

  protected val channels = mutable.ArrayBuffer[(Data, ChannelType[_ <: Data], ChannelMetadata)]()
  lazy private val fieldToChannelMap = Map((channels.map(t => t._1 -> t._2)):_*)
  def reverseElementMap = elements.map({ case (chName, chField) => chField -> chName  }).toMap

  protected def getLeafDirs(token: Data): Seq[Direction] = token match {
    case c: Clock => Seq(directionOf(c))//throw new Exception("Data tokens cannot contain clock fields")
    case b: Record => b.elements.flatMap({ case (_, e) => getLeafDirs(e)}).toSeq
    case v: Vec[_] => v.flatMap(getLeafDirs)
    case b: Bits => Seq(directionOf(b))
  }

  //// Call in port definition to register a field as belonging to a unique channel
  //private def checkAllFieldsAssignedToChannels(): Unit = {
  //  def prefixWith(prefix: String, base: Any): String =
  //    if (prefix != "")  s"${prefix}.${base}" else base.toString

  //  def loop(name: String, field: Data): Seq[(String, Boolean)] = field match {
  //    case c: Clock => Seq(name -> true)
  //    case b: Record if fieldToChannelMap.isDefinedAt(b) => Seq(name -> true)
  //    case b: Record => b.elements.flatMap({ case (n, e) => loop(prefixWith(name, n), e) }).toSeq
  //    case v: Vec[_] if fieldToChannelMap.isDefinedAt(v) => Seq(name -> true)
  //    case v: Vec[_] => (v.zipWithIndex).flatMap({ case (e, i) => loop(s"${name}_$i", e) })
  //    case b: Bits => Seq(name -> fieldToChannelMap.isDefinedAt(b))
  //  }
  //  val messages = loop("", targetPortProto).collect({ case (name, assigned) if !assigned =>
  //    "Field ${name} of bridge IO is not assigned to a channel"})
  //  assert(messages.isEmpty, messages.mkString("\n"))
  //}

  def checkFieldDirection(field: Data, direction: Direction): Unit = {
    val directions = getLeafDirs(field)
    val isUnidirectional = directions.zip(directions.tail).map({ case (a, b) => a == b }).foldLeft(true)(_ && _)
    require(isUnidirectional, "Token channels must have a unidirectioned payload")
    require(directions.head == direction)
  }

  // Simplifying assumption: if the user wants to aggregate a bunch of wires
  // into a single channel they should aggregate them into a Bundle on their record
  protected def channelField[T <: Data](direction: Direction, field: T): ChannelType[T] = {
    checkFieldDirection(field, direction)
    val ch = direction match {
      case Direction.Input => Flipped(payloadWrapper(field.cloneType))
      case Direction.Output => payloadWrapper(field.cloneType)
    }
    ch
  }

  def generateAnnotations(): Unit = {
    for ((targetField, channelElement, metadata) <- channels) {
      metadata.generateAnnotations(reverseElementMap(channelElement)).map(a => annotate(a))
    }
  }

  def connectChannels2Port(bridgeAnno: BridgeIOAnnotation, simWrapper: SimWrapperChannels): Unit = {
    val local2globalName = bridgeAnno.channelMapping.toMap
    for ((_, channel, metadata) <- channels) {
      val localName = reverseElementMap(channel)
      if (metadata.bridgeSunk) {
        channel <> simWrapper.wireOutputPortMap(local2globalName(localName))
      } else {
        simWrapper.wireInputPortMap(local2globalName(localName)) <> channel
      }
    }
  }

  def allChannelNames() = channels.map(ch => reverseElementMap(ch._2))
}

trait ChannelizedHostPortIO extends IndependentChannels { this: Record =>
  type ChannelType[A <: Data] = DecoupledIO[A]
  def payloadWrapper[A <: Data](payload: A): ChannelType[A] = Decoupled(payload)
  def InputChannel[A <: Data](field: A): ChannelType[A] = {
    val ch = channelField(Direction.Input, field)
    channels.append((field, ch, PipeChannelMetadata(field, getClock, bridgeSunk = true)))
    ch
  }
  def OutputChannel[A <: Data](field: A): ChannelType[A] = {
    val ch = channelField(Direction.Output, field)
    channels.append((field, ch, PipeChannelMetadata(field, getClock, bridgeSunk = false)))
    ch
  }
}

trait TimestampedHostPortIO extends IndependentChannels { this: Record =>
  type ChannelType[A <: Data] = DecoupledIO[TimestampedToken[A]]
  def payloadWrapper[A <: Data](payload: A): ChannelType[A] = Decoupled(new TimestampedToken(payload))
  protected def clockChannelPort(direction: Direction, field: Clock): DecoupledIO[TimestampedToken[Bool]] = {
    // FIXME: WHen the target interface is regenerated during compilation direction information is lost.
    //checkFieldDirection(field, direction)
    val ch = direction match {
      case Direction.Input => Flipped(Decoupled(new TimestampedToken(Bool())))
      case Direction.Output => Decoupled(new TimestampedToken(Bool()))
    }
    channels.append((field, ch, ClockChannelMetadata(field, direction == Direction.Input)))
    ch
  }

  def InputClockChannel(field: Clock): ChannelType[Bool] = clockChannelPort(Direction.Input, field)
  def OutputClockChannel(field: Clock): ChannelType[Bool] = clockChannelPort(Direction.Output, field)
  def OutputClockVecChannel(field: Vec[Clock]): ChannelType[Vec[Bool]] = {
    val ch = Decoupled(new TimestampedToken((Vec(field.length, Bool()))))
    channels.append((field, ch, ClockChannelMetadata(field, bridgeSunk = false)))
    ch
  }

  // For non-clock, non-async reset singals
  def InputChannel[A <: Data](field: A): ChannelType[A] = {
    val ch = channelField(Direction.Input, field)
    channels.append((field, ch, ClockControlChannelMetadata(field, bridgeSunk = true)))
    ch
  }
  def OutputChannel[A <: Data](field: A): ChannelType[A] = {
    val ch = channelField(Direction.Output, field)
    channels.append((field, ch, ClockControlChannelMetadata(field, bridgeSunk = false)))
    ch
  }
}
