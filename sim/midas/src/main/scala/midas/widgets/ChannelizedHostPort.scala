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

/**
  * A container for all of the data required to build a [[fame.FAMEChannelConnectionAnnotation]]
  */
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

case class PipeChannelMetadata(field: Data, clock: Clock, bridgeSunk: Boolean, latency: Int = 0) extends ChannelMetadata {
  def fieldRTs = Seq(field.toTarget)
  def clockRT = Some(clock.toTarget)
  def chInfo = midas.passes.fame.PipeChannel(latency)
}

private [midas] trait IndependentChannels extends HasChannels { this: Record =>
  /**
    * Contains the mapping from target interface elements to channels
    * _._1 -> A reference to the target signal
    * _._2 -> A reference to the channel element in this record
    * _._3 -> Metadata associated with the channel (used for FCCA generation
    */
  protected val channels = mutable.ArrayBuffer[(Data, DecoupledIO[Data], ChannelMetadata)]()
  lazy private val fieldToChannelMap = Map((channels.map(t => t._1 -> t._2)):_*)
  def reverseElementMap = elements.map({ case (chName, chField) => chField -> chName  }).toMap

  protected def getLeafDirs(token: Data): Seq[Direction] = token match {
    case c: Clock => Seq(directionOf(c))
    case b: Record => b.elements.flatMap({ case (_, e) => getLeafDirs(e)}).toSeq
    case v: Vec[_] => v.flatMap(getLeafDirs)
    case b: Bits => Seq(directionOf(b))
  }

  def checkFieldDirection(field: Data, direction: Direction): Unit = {
    val directions = getLeafDirs(field)
    val isUnidirectional = directions.zip(directions.tail).map({ case (a, b) => a == b }).foldLeft(true)(_ && _)
    require(isUnidirectional, "Token channels must have a unidirectioned payload")
    require(directions.head == direction)
  }

  // Simplifying assumption: if the user wants to aggregate a bunch of wires
  // into a single channel they should aggregate them into a Bundle on their record
  protected def channelField[T <: Data](gen: => T, direction: Direction): DecoupledIO[T] = direction match {
    case Direction.Input => Flipped(Decoupled(gen))
    case Direction.Output => Decoupled(gen)
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

  def allChannelNames: Seq[String] = channels.map(ch => reverseElementMap(ch._2))
}

/* 
trait ChannelizedHostPortIO extends IndependentChannels { this: Record =>
  def targetClockRef: Clock
  private def channel[A <: Data](direction: Direction, field: A): DecoupledIO[A] = {
    val ch = channelField(field.cloneType, direction)
    val meta = PipeChannelMetadata(field, targetClockRef, bridgeSunk = direction == Direction.Output)
    channels.append((field, ch, meta))
    ch
  }

  def OutputChannel[A <: Data](field: A): DecoupledIO[A] = channel(Direction.Output, field)
  def InputChannel[A <: Data](field: A): DecoupledIO[A] = channel(Direction.Input, field)
}
