// See LICENSE for license details.

package midas.widgets

import midas.core.{TargetChannelIO}

import midas.passes.fame.{FAMEChannelInfo, FAMEChannelConnectionAnnotation}

import chisel3._
import chisel3.util._
import chisel3.experimental.{Direction, ChiselAnnotation, annotate}
import chisel3.experimental.DataMirror.directionOf

import firrtl.annotations.ReferenceTarget

import scala.collection.mutable

/**
  * A utility trait for translating chisel references into unidirected FCCAs
  * This becomes more useful when there are channel types.
  *
  */
case class PipeChannelMetadata(field: Data, clock: Clock, bridgeSunk: Boolean, latency: Int = 0) {
  def fieldRTs = Seq(field.toTarget)
  def clockRT = clock.toTarget
  def chInfo = midas.passes.fame.PipeChannel(latency)
}

/**
  * A host-side bridge interface trait that permits finer-grained control over
  * channel definition versus [[HostPortIO]]. Required for describing bridges
  * that are combinationally coupled to the target.
  *
  */
trait ChannelizedHostPortIO extends HasChannels { this: Record =>
  // All channels in a bridge are "tokenized" on the positive edge of a single
  // clock. With this, the user provides a reference to a clock, either on the
  // target-side bridge itself or elsewhere in the target, which will encode
  // this relationship in emitted channel annotations.
  def targetClockRef: Clock

  type ChannelType[A <: Data] = DecoupledIO[A]
  // This is built up with invocations to [[InputChannel]] and [[OutputChannel]]
  // _1 -> A reference to the target field (directioned if elaborated as part
  //       of the target-side of the bridge) 
  // _2 -> The associated host-channel (an actual element in this aggregate, unlike above)
  // _3 -> Associated metadate which will encode for the FCCA
  private val channels = mutable.ArrayBuffer[(Data, ChannelType[_ <: Data], PipeChannelMetadata)]()

  // These will only be called after the record has been finalized.
  lazy private val fieldToChannelMap = Map((channels.map(t => t._1 -> t._2)):_*)
  private def reverseElementMap = elements.map({ case (chName, chField) => chField -> chName  }).toMap

  private def getLeafDirs(token: Data): Seq[Direction] = token match {
    case c: Clock => Seq(directionOf(c))
    case b: Record => b.elements.flatMap({ case (_, e) => getLeafDirs(e)}).toSeq
    case v: Vec[_] => v.flatMap(getLeafDirs)
    case b: Bits => Seq(directionOf(b))
  }

  private def checkFieldDirection(field: Data, bridgeSunk: Boolean): Unit = {
    val directions = getLeafDirs(field)
    val isUnidirectional = directions.zip(directions.tail).map({ case (a, b) => a == b }).foldLeft(true)(_ && _)
    require(isUnidirectional, "Token channels must have a unidirectioned payload")
    val channelDir = if (bridgeSunk) Direction.Input else Direction.Output
    require(directions.head == channelDir,
      s"Direction of target fields ${directions.head} must match direction of requested channel.")
  }

  // Simplifying assumption: if the user wants to aggregate a bunch of wires
  // into a single channel they should aggregate them into a Bundle on their record
  private def channelField[T <: Data](direction: Direction, field: T): ChannelType[T] = {
    val ch = direction match {
      case Direction.Input => Flipped(Decoupled(field.cloneType))
      case Direction.Output => Decoupled(field.cloneType)
      case _ => throw new Exception("Channel direction must be Input or Output")
    }
    ch
  }

  /**
    * Marks an input to the bridge as being a distinct channel. It will become a
    * bridge-sunk ready-valid interface on this host port definition, with the
    * target datatype as its payload.
    *
    * @param field A field in the target interface that corresponds to a channel.F
    *
    */
  def InputChannel[A <: Data](field: A): ChannelType[A] = {
    val ch = channelField(Direction.Input, field)
    channels.append((field, ch, PipeChannelMetadata(field, targetClockRef, bridgeSunk = true)))
    ch
  }

  /**
    * The reverse of [[ChannelizedHostPortIO.InputChannel]], in that it marks
    * an output to the bridge as being a distinct channel.
    *
    * @param field A field in the target interface that corresponds to a channel.
    *
    */
  def OutputChannel[A <: Data](field: A): ChannelType[A] = {
    val ch = channelField(Direction.Output, field)
    channels.append((field, ch, PipeChannelMetadata(field, targetClockRef, bridgeSunk = false)))
    ch
  }

  def connectChannels2Port(bridgeAnno: BridgeIOAnnotation, targetIO: TargetChannelIO): Unit = {
    val local2globalName = bridgeAnno.channelMapping.toMap
    for ((_, channel, metadata) <- channels) {
      val localName = reverseElementMap(channel)
      if (metadata.bridgeSunk) {
        channel <> targetIO.wireOutputPortMap(local2globalName(localName))
      } else {
        targetIO.wireInputPortMap(local2globalName(localName)) <> channel
      }
    }
  }

  def bridgeChannels = channels.map({ case (targetField, ch, meta) =>
    val name = reverseElementMap(ch)
    checkFieldDirection(targetField, meta.bridgeSunk)
    if (meta.bridgeSunk) {
      PipeBridgeChannel(name, meta.clockRT, Seq(), meta.fieldRTs, 0)
    } else {
      PipeBridgeChannel(name, meta.clockRT, meta.fieldRTs, Seq(), 0)
    }
  })

  // def getOutputChannelPorts() = channels.map({ case (targetField, ch, meta) =>
  //   ch
  // })

  // outputs only
  def getOutputChannelPorts() = channels.collect({ case (targetField, ch, meta) if !meta.bridgeSunk =>
    (reverseElementMap(ch), ch)
  })


  // inputs only
  def getInputChannelPorts() = channels.collect({ case (targetField, ch, meta) if meta.bridgeSunk =>
    (reverseElementMap(ch), ch)
  })

  // def tokenHashers() = {
  //   println("CALLED tokenHashers from ChannelizedHostPort")
  //   // // val yYYY = 0x0eadbeef.U
  //   // val (zZZZOunter, counterWrap) = Counter.apply(true.B, 1024)
  //   // val ZZZcounterValid = true.B
  //   // val ZZZcounterHash = XORHash32(zZZZOunter, ZZZcounterValid)
  //   // chisel3.assert(ZZZcounterHash===RegNext(ZZZcounterHash))

  //   channels.map({ case (targetField, ch, meta) =>
  //     val name = reverseElementMap(ch)
  //     // println("bridgeChannels:")
  //     // println(name)
  //     // checkFieldDirection(targetField, meta.bridgeSunk)
  //     if (meta.bridgeSunk) {
  //       println(s"INPUT Channel ${name}")
  //       // PipeBridgeChannel(name, meta.clockRT, Seq(), meta.fieldRTs, 0)
  //     } else {
  //       println(s"OUTPUT Channel ${name}")
  //       // PipeBridgeChannel(name, meta.clockRT, meta.fieldRTs, Seq(), 0)

  //       val ZZZoutputChannelHash = XORHash32(ch.bits, ch.valid)
  //       chisel3.assert(ZZZoutputChannelHash===RegNext(ZZZoutputChannelHash))

  //       // val readHash = genWOReg(ZZZoutputChannelHash, s"readHash${name}")
  //     }
  //   })

  //   Unit
  // }
}
