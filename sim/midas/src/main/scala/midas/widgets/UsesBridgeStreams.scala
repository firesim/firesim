// See LICENSE for license details.

package midas.widgets

import chisel3._
import chisel3.util._
import chisel3.experimental.IO
import freechips.rocketchip.diplomacy.InModuleBody

import midas.core.{
  ToCPUStreamAllocatorKey,
  FromCPUStreamAllocatorKey,
  StreamSinkParameters,
  StreamSourceParameters,
}

/**
  * Bridge Streams serve as means to do bulk transport from BridgeDriver to
  * BridgeModule and vice versa.  Abstractly, they can be thought of as a 512b
  * wide latency-insensitive channel (i.e., a queue with some unknown latency).
  *
  * The two mixins in this file implement the two directions of
  * producer-consumer relationships: [[StreamFromHostCPU]] add a stream in
  * which the driver is the producer and the BridgeModule is the consumer,
  * [[StreamToHostCPU]] does the converse. BridgeModules can mix in one or both
  * of these traits, to implement streams in either direction.
  *
  * Limitations:
  * - Streams are 512b wide. Bridge modules and drivers must manually handle
  *   width adaptations
  * - Bridges are limited to one stream in each direction. Bridge designers
  *   must multiplex streams onto a single physical stream themselves.
  */

/**
  * Constants defined here apply to interfaces exposed directly to the bridges
  * and thus users and bridge designers.
  */
object BridgeStreamConstants {
  /** The width, in bits, of the decoupled UInt payload presented to the bridge. */
  val streamWidthBits = 512

  /** Sugar for generating a chisel type of a stream */
  def streamChiselType = DecoupledIO(UInt(streamWidthBits.W))
}

/**
  *  Adds a stream interface that will be dequeued from by the BridgeModule.
  */
trait StreamFromHostCPU { self: Widget =>
  // It may not make sense to keep this common under all stream engine
  // implementations, but for now this minimize the diff in bridge module code.
  def fromHostCPUQueueDepth: Int

  final val (fromHostStreamName, fromHostStreamIdx) = p(FromCPUStreamAllocatorKey)
    .allocate(s"${getWName.toUpperCase}_from_cpu_stream")

  final def streamSinkParams = StreamSinkParameters(
    fromHostStreamName,
    fromHostStreamIdx,
    fromHostCPUQueueDepth)


  private val _streamDeq = InModuleBody {
    val streamFromHostCPU = IO(Flipped(BridgeStreamConstants.streamChiselType))
    streamFromHostCPU
  }

  // This hides some diplomacy complexity from the user in cases where the
  // implicit conversion from the wrapped value to the decoupled does not work.
  def streamDeq = _streamDeq.getWrappedValue

  appendHeaderFragment { _ => Seq(
      CppGenerationUtils.genConstStatic(s"${fromHostStreamName}_idx", UInt32(fromHostStreamIdx)),
      CppGenerationUtils.genConstStatic(s"${fromHostStreamName}_depth", UInt32(fromHostCPUQueueDepth))
    )
  }
}

/**
  *  Adds a stream interface that will be enqueued to by the BridgeModule.
  */
trait StreamToHostCPU { self: Widget =>
  def toHostCPUQueueDepth: Int

  final val (toHostStreamName, toHostStreamIdx) = p(ToCPUStreamAllocatorKey)
    .allocate(s"${getWName.toUpperCase}_to_cpu_stream")

  final def streamSourceParams = StreamSourceParameters(
    toHostStreamName,
    toHostStreamIdx,
    toHostCPUQueueDepth)

  private val _streamEnq = InModuleBody {
    val streamToHostCPU = IO(BridgeStreamConstants.streamChiselType)

    streamToHostCPU
  }

  appendHeaderFragment { _ => Seq(
      CppGenerationUtils.genConstStatic(s"${toHostStreamName}_idx", UInt32(toHostStreamIdx)),
      CppGenerationUtils.genConstStatic(s"${toHostStreamName}_depth", UInt32(toHostCPUQueueDepth))
    )
  }

  // This hides some diplomacy complexity from the user in cases where the
  // implicit conversion from the wrapped value to the decoupled does not work.
  def streamEnq = _streamEnq.getWrappedValue
}
