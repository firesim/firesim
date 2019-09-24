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

case class TracedInstructionWidths(iaddr: Int, insn: Int, cause: Int, tval: Int)

object TracedInstructionWidths {
  def apply(tI: TracedInstruction): TracedInstructionWidths =
    TracedInstructionWidths(tI.iaddr.getWidth, tI.insn.getWidth, tI.cause.getWidth, tI.tval.getWidth)
}

// Hack: In a457f658a, RC added the Clocked trait to TracedInstruction, which breaks midas
// I/O token handling. The non-Clock fields of this Bundle should be factored out in rocket chip.
// For now, we create second Bundle with Clock (of type Clock) and Reset removed
class DeclockedTracedInstruction(val widths: TracedInstructionWidths) extends Bundle {
  val valid = Bool()
  val iaddr = UInt(widths.iaddr.W)
  val insn = UInt(widths.insn.W)
  val priv = UInt(width = 3.W)
  val exception = Bool()
  val interrupt = Bool()
  val cause = UInt(widths.cause.W)
  val tval = UInt(widths.tval.W)
}

object DeclockedTracedInstruction {
  def apply(tI: TracedInstruction): DeclockedTracedInstruction =
    new DeclockedTracedInstruction(TracedInstructionWidths(tI))

  // Generates a hardware Vec of declockedInsns
  def fromVec(clockedVec: Vec[TracedInstruction]): Vec[DeclockedTracedInstruction] = {
    val declockedVec = clockedVec.map(insn => Wire(DeclockedTracedInstruction(insn.cloneType)))
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
    case (bundle, _) => Vec(bundle.length, DeclockedTracedInstruction(bundle.head.cloneType))
  })
}

// The IO matched on by the TracerV endpoint: a wrapper around a heterogenous
// bag of vectors. Each entry is Vec of committed instructions
class TraceOutputTop(private val traceProto: Seq[Vec[DeclockedTracedInstruction]]) extends Bundle {
  val traces = Output(HeterogeneousBag(traceProto.map(_.cloneType)))
  def getProto() = traceProto
  def getWidths(): Seq[TracedInstructionWidths] = traceProto.map(_.head.widths)
  def getVecSizes(): Seq[Int] = traceProto.map(_.size)
}

object TraceOutputTop {
  def apply(widths: Seq[TracedInstructionWidths], vecSizes: Seq[Int]): TraceOutputTop =
    new TraceOutputTop(vecSizes.zip(widths).map({ case (size, w) =>
      Vec(size, new DeclockedTracedInstruction(w))
    }))
}

case class TracerVKey(
  insnWidths: Seq[TracedInstructionWidths], // Widths of variable length fields in each TI
  vecSizes: Seq[Int] // The number of insns in each vec (= max insns retired at that core) 
)

class TracerVEndpoint(traceProto: Seq[Vec[DeclockedTracedInstruction]]) extends BlackBox
    with Endpoint[HostPortIO[TraceOutputTop], TracerVWidget] {
  val io = IO(Flipped(new TraceOutputTop(traceProto)))
  val endpointIO = HostPort(io)
  val constructorArg = Some(TracerVKey(io.getWidths, io.getVecSizes))
  generateAnnotations()
}

object TracerVEndpoint {
  def apply(port: TraceOutputTop)(implicit p:Parameters): Seq[TracerVEndpoint] = {
    val ep = Module(new TracerVEndpoint(port.getProto))
    ep.io <> port
    Seq(ep)
  }
}

class TracerVWidget(key: TracerVKey)(implicit p: Parameters) extends EndpointWidget[HostPortIO[TraceOutputTop]]()(p)
    with UnidirectionalDMAToHostCPU {
  val io = IO(new WidgetIO)
  val hPort = IO(HostPort(Flipped(TraceOutputTop(key.insnWidths, key.vecSizes))))

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
