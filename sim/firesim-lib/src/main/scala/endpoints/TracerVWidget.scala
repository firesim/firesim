//See LICENSE for license details
package firesim.endpoints

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.util._
import freechips.rocketchip.rocket.TracedInstruction
import freechips.rocketchip.subsystem.RocketTilesKey
import freechips.rocketchip.tile.TileKey

import midas.widgets._
import testchipip.{StreamIO, StreamChannel}
import icenet.{NICIOvonly, RateLimiterSettings}
import icenet.IceNIC._
import junctions.{NastiIO, NastiKey}
import TokenQueueConsts._

// Hack: In a457f658a, RC added the Clocked trait to TracedInstruction, which breaks midas
// I/O token handling. The non-Clock fields of this Bundle should be factored out in rocket chip.
// For now, we create second Bundle with Clock (of type Clock) and Reset removed
class DeclockedTracedInstruction(private val proto: TracedInstruction) extends Bundle {
  val valid = Bool()
  val iaddr = UInt(proto.iaddr.getWidth.W)
  val insn  = UInt(proto.insn.getWidth.W)
  val priv  = UInt(proto.priv.getWidth.W)
  val exception = Bool()
  val interrupt = Bool()
  val cause = UInt(proto.cause.getWidth.W)
  val tval = UInt(proto.tval.getWidth.W)
}

object DeclockedTracedInstruction {
  // Generates a hardware Vec of declockedInsns
  def fromVec(clockedVec: Vec[TracedInstruction]): Vec[DeclockedTracedInstruction] = {
    val declockedVec = clockedVec.map(insn => Wire(new DeclockedTracedInstruction(insn.cloneType)))
    declockedVec.zip(clockedVec).foreach({ case (declocked, clocked) =>
      declocked.valid := clocked.valid
      declocked.iaddr := clocked.iaddr
      declocked.insn := clocked.insn
      declocked.priv := clocked.priv
      declocked.exception := clocked.exception
      declocked.interrupt := clocked.interrupt
      declocked.cause := clocked.cause
      declocked.tval := clocked.tval
    })
    VecInit(declockedVec)
  }

  // Generates a Chisel type from that returned by a Diplomatic node's in() or .out() methods
  def fromNode(ports: Seq[(Vec[TracedInstruction], Any)]): Seq[Vec[DeclockedTracedInstruction]] = ports.map({
    case (bundle, _) => Vec(bundle.length, new DeclockedTracedInstruction(bundle.head.cloneType))
  })
}

// The IO matched on by the TracerV endpoint: a wrapper around a heterogenous
// bag of vectors. Each entry is Vec of committed instructions
class TraceOutputTop(private val traceProto: Seq[Vec[DeclockedTracedInstruction]]) extends Bundle {
  val traces = Output(HeterogeneousBag(traceProto.map(_.cloneType)))
  def getProto() = traceProto
}

class TracerVEndpoint(traceProto: Seq[Vec[DeclockedTracedInstruction]]) extends BlackBox with IsEndpoint {
  val io = IO(Flipped(new TraceOutputTop(traceProto)))
  val endpointIO = HostPort(io)
  def widget = (p: Parameters) => new TracerVWidget(traceProto)(p)
  generateAnnotations()
}

object TracerVEndpoint {
  def apply(port: TraceOutputTop)(implicit p:Parameters): Seq[TracerVEndpoint] = {
    val ep = Module(new TracerVEndpoint(port.getProto))
    ep.io <> port
    Seq(ep)
  }
}

class TracerVWidget(traceProto: Seq[Vec[DeclockedTracedInstruction]])(implicit p: Parameters) extends EndpointWidget()(p)
    with UnidirectionalDMAToHostCPU {
  val io = IO(new WidgetIO)
  val hPort = IO(HostPort(Flipped(new TraceOutputTop(traceProto))))

  // DMA mixin parameters
  lazy val toHostCPUQueueDepth  = TOKEN_QUEUE_DEPTH
  lazy val dmaSize = BigInt((BIG_TOKEN_WIDTH / 8) * TOKEN_QUEUE_DEPTH)

  val uint_traces = hPort.hBits.traces map (trace => trace.asUInt)
  outgoingPCISdat.io.enq.bits := Cat(uint_traces)

  val tFireHelper = DecoupledHelper(outgoingPCISdat.io.enq.ready,
    hPort.toHost.hValid, hPort.fromHost.hReady)

  hPort.fromHost.hValid := tFireHelper.fire(hPort.fromHost.hReady)
  hPort.toHost.hReady := tFireHelper.fire

  outgoingPCISdat.io.enq.valid := tFireHelper.fire(outgoingPCISdat.io.enq.ready)

  // This need to go on a debug switch
  //when (outgoingPCISdat.io.enq.fire()) {
  //  hPort.hBits.traces.zipWithIndex.foreach({ case (bundle, bIdx) =>
  //    printf("Tile %d Trace Bundle\n", bIdx.U)
  //    bundle.zipWithIndex.foreach({ case (insn, insnIdx) =>
  //      printf(p"insn ${insnIdx}: ${insn}\n")
  //      //printf(b"insn ${insnIdx}, valid: ${insn.valid}")
  //      //printf(b"insn ${insnIdx}, iaddr: ${insn.iaddr}")
  //      //printf(b"insn ${insnIdx}, insn: ${insn.insn}")
  //      //printf(b"insn ${insnIdx}, priv:  ${insn.priv}")
  //      //printf(b"insn ${insnIdx}, exception: ${insn.exception}")
  //      //printf(b"insn ${insnIdx}, interrupt: ${insn.interrupt}")
  //      //printf(b"insn ${insnIdx}, cause: ${insn.cause}")
  //      //printf(b"insn ${insnIdx}, tval: ${insn.tval}")
  //    })
  //  })
  //}
  attach(outgoingPCISdat.io.deq.valid && !outgoingPCISdat.io.enq.ready, "tracequeuefull", ReadOnly)
  genCRFile()
}
