package firesim
package endpoints

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.util._
import freechips.rocketchip.rocket.TracedInstruction
import freechips.rocketchip.subsystem.RocketTilesKey
import freechips.rocketchip.tile.TileKey

import midas.core.{HostPort}
import midas.widgets._
import testchipip.{StreamIO, StreamChannel}
import icenet.{NICIOvonly, RateLimiterSettings}
import icenet.IceNIC._
import junctions.{NastiIO, NastiKey}
import TokenQueueConsts._

class TraceOutputTop(val numTraces: Int)(implicit val p: Parameters) extends Bundle {
  val traces = Vec(numTraces, new TracedInstruction)
}

class SimTracerV extends Endpoint {

  // this is questionable ...
  // but I can't see a better way to do this for now. getting sharedMemoryTLEdge is the problem.
  var tracer_param = Parameters.empty
  var num_traces = 0
  def matchType(data: Data) = data match {
    case channel: TraceOutputTop => {
      // this is questionable ...
      tracer_param = channel.traces(0).p
      num_traces = channel.traces.length
      true
    }
    case _ => false
  }
  def widget(p: Parameters) = new TracerVWidget(tracer_param, num_traces)(p)
  override def widgetName = "TracerVWidget"
}

class TracerVWidgetIO(val tracerParams: Parameters, val num_traces: Int)(implicit p: Parameters) extends EndpointWidgetIO()(p) {
  val hPort = Flipped(HostPort(new TraceOutputTop(num_traces)(tracerParams)))
}

class TracerVWidget(tracerParams: Parameters, num_traces: Int)(implicit p: Parameters) extends EndpointWidget()(p)
    with UnidirectionalDMAToHostCPU {
  val io = IO(new TracerVWidgetIO(tracerParams, num_traces))

  // DMA mixin parameters
  lazy val toHostCPUQueueDepth  = TOKEN_QUEUE_DEPTH
  lazy val dmaSize = BigInt((BIG_TOKEN_WIDTH / 8) * TOKEN_QUEUE_DEPTH)

  val uint_traces = io.hPort.hBits.traces map (trace => trace.asUInt)
  outgoingPCISdat.io.enq.bits := Cat(uint_traces) //io.hPort.hBits.traces(0).asUInt

  val tFireHelper = DecoupledHelper(outgoingPCISdat.io.enq.ready,
    io.hPort.toHost.hValid, io.hPort.fromHost.hReady, io.tReset.valid)

  io.tReset.ready := tFireHelper.fire(io.tReset.valid)
  io.hPort.fromHost.hValid := tFireHelper.fire(io.hPort.fromHost.hReady)
  io.hPort.toHost.hReady := tFireHelper.fire(io.hPort.toHost.hValid)

  outgoingPCISdat.io.enq.valid := tFireHelper.fire(outgoingPCISdat.io.enq.ready)

  when (outgoingPCISdat.io.enq.fire()) {
    for (i <- 0 until io.hPort.hBits.traces.length) {
      printf("trace %d, valid: %x\n", i.U, io.hPort.hBits.traces(i).valid)
      printf("trace %d, iaddr: %x\n", i.U, io.hPort.hBits.traces(i).iaddr)
      printf("trace %d, insn: %x\n", i.U, io.hPort.hBits.traces(i).insn)
      printf("trace %d, priv: %x\n", i.U, io.hPort.hBits.traces(i).priv)
      printf("trace %d, exception: %x\n", i.U, io.hPort.hBits.traces(i).exception)
      printf("trace %d, interrupt: %x\n", i.U, io.hPort.hBits.traces(i).interrupt)
      printf("trace %d, cause: %x\n", i.U, io.hPort.hBits.traces(i).cause)
      printf("trace %d, tval: %x\n", i.U, io.hPort.hBits.traces(i).tval)
    }
  }
  attach(outgoingPCISdat.io.deq.valid && !outgoingPCISdat.io.enq.ready, "tracequeuefull", ReadOnly)
  genCRFile()
}
