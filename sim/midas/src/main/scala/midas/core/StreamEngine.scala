// See LICENSE for license details.

package midas.core

import chisel3._
import chisel3.experimental.IO
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy._

import midas.widgets._

/**
  * Set by a platform config to instantiate the supported StreamEnginer for
  * that host.  e.g. F1 uses CPU-driven XDMA and so uses an engine that only
  * uses the AXI4M interface.
  */
case object StreamEngineInstantiatorKey extends Field[(StreamEngineParameters, Parameters) => StreamEngine]


/**
  * Parameters that define a stream that are defined by bridges and collected
  * by the engine.
  */
trait StreamParameters {
  def name: String
  def idx: Int
  def fpgaBufferDepth: Int
  /**
    * Pretty prints a description of this stream.
    */
  def summaryString: String =
    s"${name}, FPGA Buffer Depth: ${fpgaBufferDepth} Beats"
}

case class StreamSourceParameters(name: String, idx: Int, fpgaBufferDepth: Int) extends StreamParameters
case class StreamSinkParameters  (name: String, idx: Int, fpgaBufferDepth: Int) extends StreamParameters

/**
  * A wrapper class for common arguments to all StreamEngine implementations.
  */
case class StreamEngineParameters(
  toCPUParams: Seq[StreamSourceParameters],
  fromCPUParams: Seq[StreamSinkParameters],
)

/**
  * Base class for StreamEngine implementations. StreamEngines collect all
  * streams from bridges, which drive two Vec[Decoupled[UInt]], and implement
  * the transport using an AXI4 slave and / or AXI4 master port, which is
  * presented by the host platform.
  *
  * Implementations that require an AXI4 subordinate set cpuManagedAXI4NodeOpt = Some(<node graph>)
  * Implementations that require an AXI4 manager set fpgaManagedAXI4NodeOpt  = Some(<node graph>)
  *
  */
abstract class StreamEngine(
    p: Parameters,
  ) extends Widget()(p) {
  def params: StreamEngineParameters
  def cpuManagedAXI4NodeOpt: Option[AXI4InwardNode]
  def fpgaManagedAXI4NodeOpt: Option[AXI4OutwardNode]


  lazy val StreamEngineParameters(sourceParams, sinkParams) = params
  def hasStreams: Boolean = sourceParams.nonEmpty || sinkParams.nonEmpty

  // Connections to bridges that drive streams
  val streamsToHostCPU = InModuleBody {
    val streamsToHostCPU = IO(Flipped(Vec(sourceParams.size, BridgeStreamConstants.streamChiselType)))
    streamsToHostCPU
  }

  // Connections to bridges that sink streams
  val streamsFromHostCPU = InModuleBody {
    val streamsFromHostCPU = IO(Vec(sinkParams.size, BridgeStreamConstants.streamChiselType))
    streamsFromHostCPU
  }
}
