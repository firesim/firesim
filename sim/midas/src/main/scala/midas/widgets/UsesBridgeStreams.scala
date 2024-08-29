// See LICENSE for license details.

package midas.widgets

import chisel3._
import chisel3.util._
import chisel3.experimental.IO
import freechips.rocketchip.diplomacy.InModuleBody

import midas.core.{
  FromCPUStreamAllocatorKey,
  FromQSFPStreamAllocatorKey,
  StreamSinkParameters,
  StreamSourceParameters,
  ToCPUStreamAllocatorKey,
  ToPeerFPGAStreamAllocatorKey,
  ToQSFPStreamAllocatorKey,
}

/** Bridge Streams serve as means to do bulk transport from BridgeDriver to BridgeModule and vice versa. Abstractly,
  * they can be thought of as a 512b wide latency-insensitive channel (i.e., a queue with some unknown latency).
  *
  * The two mixins in this file implement the two directions of producer-consumer relationships: [[StreamFromHostCPU]]
  * add a stream in which the driver is the producer and the BridgeModule is the consumer, [[StreamToHostCPU]] does the
  * converse. BridgeModules can mix in one or both of these traits, to implement streams in either direction.
  *
  * Limitations:
  *   - Streams are 512b wide. Bridge modules and drivers must manually handle width adaptations
  *   - Bridges are limited to one stream in each direction. Bridge designers must multiplex streams onto a single
  *     physical stream themselves.
  */

/** Constants defined here apply to interfaces exposed directly to the bridges and thus users and bridge designers.
  */
object BridgeStreamConstants {

  /** The width, in bits, of the decoupled UInt payload presented to the bridge. */
  val streamWidthBits = 512

  /** Sugar for generating a chisel type of a stream */
  def streamChiselType = DecoupledIO(UInt(streamWidthBits.W))
}

abstract trait StreamFrom { self: Widget =>
  def streamDeq:               chisel3.Data
  def fromHostStreamWidthBits: Int
}

abstract trait StreamTo { self: Widget =>
  def streamEnq:             chisel3.Data
  def toHostStreamWidthBits: Int
}

/** Adds a stream interface that will be dequeued from by the BridgeModule.
  */
trait StreamFromHostCPU extends StreamFrom { self: Widget =>
  // It may not make sense to keep this common under all stream engine
  // implementations, but for now this minimize the diff in bridge module code.
  def fromHostCPUQueueDepth: Int

  final val (fromHostStreamName, fromHostStreamIdx) = p(FromCPUStreamAllocatorKey)
    .allocate(s"${getWName.toUpperCase}_from_cpu_stream")

  final def streamSinkParams = StreamSinkParameters(
    fromHostStreamName,
    fromHostStreamIdx,
    fromHostCPUQueueDepth,
    BridgeStreamConstants.streamWidthBits / 8,
  )

  private val _streamDeq = InModuleBody {
    val streamFromHostCPU = IO(Flipped(BridgeStreamConstants.streamChiselType))
    streamFromHostCPU
  }

  // This hides some diplomacy complexity from the user in cases where the
  // implicit conversion from the wrapped value to the decoupled does not work.
  override def streamDeq = _streamDeq.getWrappedValue

  override def fromHostStreamWidthBits = BridgeStreamConstants.streamWidthBits
}

/** Adds a stream interface that will be enqueued to by the BridgeModule.
  */
trait StreamToHostCPU extends StreamTo { self: Widget =>
  def toHostCPUQueueDepth: Int

  final val (toHostStreamName, toHostStreamIdx) = p(ToCPUStreamAllocatorKey)
    .allocate(s"${getWName.toUpperCase}_to_cpu_stream")

  final def streamSourceParams = StreamSourceParameters(
    toHostStreamName,
    toHostStreamIdx,
    toHostCPUQueueDepth,
    BridgeStreamConstants.streamWidthBits / 8,
  )

  private val _streamEnq = InModuleBody {
    val streamToHostCPU = IO(BridgeStreamConstants.streamChiselType)

    streamToHostCPU
  }

  // This hides some diplomacy complexity from the user in cases where the
  // implicit conversion from the wrapped value to the decoupled does not work.
  override def streamEnq = _streamEnq.getWrappedValue

  override def toHostStreamWidthBits = BridgeStreamConstants.streamWidthBits
}

object QSFPBridgeStreamConstants {

  /** The width, in bits, of the decoupled UInt payload presented to the bridge. */
  val streamWidthBits = 256

  /** Sugar for generating a chisel type of a stream */
  def streamChiselType = DecoupledIO(UInt(streamWidthBits.W))
}

/** Adds a stream interface that will be dequeued from by the BridgeModule.
  */
trait StreamFromQSFP extends StreamFrom { self: Widget =>
  // It may not make sense to keep this common under all stream engine
  // implementations, but for now this minimize the diff in bridge module code.
  def fromQSFPQueueDepth: Int

  final val (fromQSFPStreamName, fromQSFPStreamIdx) = p(FromQSFPStreamAllocatorKey)
    .allocate(s"${getWName.toUpperCase}_from_QSFP_stream")

  final def streamSinkParams = StreamSinkParameters(
    fromQSFPStreamName,
    fromQSFPStreamIdx,
    fromQSFPQueueDepth,
    QSFPBridgeStreamConstants.streamWidthBits / 8,
  )

  private val _streamDeq = InModuleBody {
    val streamFromQSFP = IO(Flipped(QSFPBridgeStreamConstants.streamChiselType))
    streamFromQSFP
  }

  // This hides some diplomacy complexity from the user in cases where the
  // implicit conversion from the wrapped value to the decoupled does not work.
  override def streamDeq = _streamDeq.getWrappedValue

  override def fromHostStreamWidthBits = QSFPBridgeStreamConstants.streamWidthBits
}

/** Adds a stream interface that will be enqueued to by the BridgeModule.
  */
trait StreamToQSFP extends StreamTo { self: Widget =>
  def toQSFPQueueDepth: Int

  final val (toQSFPStreamName, toQSFPStreamIdx) = p(ToQSFPStreamAllocatorKey)
    .allocate(s"${getWName.toUpperCase}_to_QSFP_stream")

  final def streamSourceParams = StreamSourceParameters(
    toQSFPStreamName,
    toQSFPStreamIdx,
    toQSFPQueueDepth,
    QSFPBridgeStreamConstants.streamWidthBits / 8,
  )

  private val _streamEnq = InModuleBody {
    val streamToQSFP = IO(QSFPBridgeStreamConstants.streamChiselType)
    streamToQSFP
  }

  // This hides some diplomacy complexity from the user in cases where the
  // implicit conversion from the wrapped value to the decoupled does not work.
  override def streamEnq = _streamEnq.getWrappedValue

  override def toHostStreamWidthBits = QSFPBridgeStreamConstants.streamWidthBits
}

/** Adds a stream interface that will be enqueued to by the BridgeModule.
  */
trait StreamToPeerFPGA extends StreamTo { self: Widget =>
  def peerFPGAMaxAddrRangeInBeats: Int

  final val (toPeerFPGAStreamName, toPeerFPGAStreamIdx) = p(ToPeerFPGAStreamAllocatorKey)
    .allocate(s"${getWName.toUpperCase}_to_PeerFPGA_stream")

  final def streamSourceParams = StreamSourceParameters(
    toPeerFPGAStreamName,
    toPeerFPGAStreamIdx,
    peerFPGAMaxAddrRangeInBeats,
    BridgeStreamConstants.streamWidthBits / 8,
  )

  private val _streamEnq = InModuleBody {
    val streamToPeerFPGA = IO(BridgeStreamConstants.streamChiselType)
    streamToPeerFPGA
  }

  // This hides some diplomacy complexity from the user in cases where the
  // implicit conversion from the wrapped value to the decoupled does not work.
  override def streamEnq = _streamEnq.getWrappedValue

  def toHostStreamWidthBits = BridgeStreamConstants.streamWidthBits
}
