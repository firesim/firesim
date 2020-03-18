//See LICENSE for license details
package firesim.bridges

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.util._
import freechips.rocketchip.rocket.TracedInstruction
import freechips.rocketchip.subsystem.RocketTilesKey
import freechips.rocketchip.tile.TileKey

import testchipip.{TraceOutputTop, DeclockedTracedInstruction, TracedInstructionWidths}

import midas.widgets._
import testchipip.{StreamIO, StreamChannel}
import junctions.{NastiIO, NastiKey}
import TokenQueueConsts._

//******
//* MISC
//******

case class DromajoKey(
  insnWidths: Seq[TracedInstructionWidths], // Widths of variable length fields in each TI
  vecSizes: Seq[Int] // The number of insns in each vec (= max insns retired at that core)
)

//*************
//* TARGET LAND
//*************

/**
 * Blackbox that is connected to the host
 */
class DromajoBridge(traceProto: Seq[Vec[DeclockedTracedInstruction]]) extends BlackBox
    with Bridge[HostPortIO[TraceOutputTop], DromajoBridgeModule]
{
  val io = IO(Flipped(new TraceOutputTop(traceProto)))
  val bridgeIO = HostPort(io)

  // give the Dromajo key to the GG module
  val constructorArg = Some(DromajoKey(io.getWidths, io.getVecSizes))

  // generate annotations to pass to GG
  generateAnnotations()
}

/**
 * Helper function to connect blackbox
 */
object DromajoBridge {
  def apply(port: TraceOutputTop)(implicit p:Parameters): Seq[DromajoBridge] = {
    val b = Module(new DromajoBridge(port.getProto))
    b.io <> port

    Seq(b)
  }
}

//*************************************************
//* GOLDEN GATE MODULE
//* This lives in the host (still runs on the FPGA)
//*************************************************

class DromajoBridgeModule(key: DromajoKey)(implicit p: Parameters) extends BridgeModule[HostPortIO[TraceOutputTop]]()(p)
    with UnidirectionalDMAToHostCPU
{
  // CONSTANTS: DMA Parameters
  lazy val QUEUE_DEPTH = 64
  lazy val TOKEN_WIDTH = 512 // in b
  lazy val toHostCPUQueueDepth = QUEUE_DEPTH
  lazy val dmaSize = BigInt((TOKEN_WIDTH / 8) * QUEUE_DEPTH)
  // CONSTANTS

  // setup io
  val io = IO(new WidgetIO)
  val hPort = IO(HostPort(Flipped(TraceOutputTop(key.insnWidths, key.vecSizes))))

  // the target is ready to both send/receive data
  val tFire = hPort.toHost.hValid && hPort.fromHost.hReady

  // helper to get number to round up to nearest multiple
  def roundUp(num: Int, mult: Int): Int = { (((num - 1) / mult) + 1) * mult }

  // get the traces
  val traces = hPort.hBits.traces.flatten
  private val iaddrWidth = roundUp(traces.map(_.iaddr.getWidth).max, 8)
  private val insnWidth  = roundUp(traces.map(_.insn.getWidth).max, 8)
  private val wdataWidth = roundUp(traces.map(_.wdata.getWidth).max, 8)
  private val causeWidth = roundUp(traces.map(_.cause.getWidth).max, 8)
  private val tvalWidth  = roundUp(traces.map(_.tval.getWidth).max, 8)

  // hack since for some reason padding a bool doesn't work...
  def boolPad(in: Bool, size: Int): UInt = {
    val temp = Wire(UInt(size.W))
    temp := in.asUInt
    temp
  }

  val paddedTraces = traces.map { trace =>
    Cat(trace.tval.pad(tvalWidth),
      Cat(trace.cause.pad(causeWidth),
        Cat(boolPad(trace.interrupt, 8),
          Cat(boolPad(trace.exception, 8),
            Cat(trace.priv.asUInt.pad(8),
              Cat(trace.wdata.pad(wdataWidth),
                Cat(trace.insn.pad(insnWidth),
                  Cat(trace.iaddr.pad(iaddrWidth), boolPad(trace.valid, 8)))))))))
  }

  val maxTraceSize = paddedTraces.map(t => t.getWidth).max
  val outDataSzBits = outgoingPCISdat.io.enq.bits.getWidth

  // constant
  val totalStreamsPerToken = 2 // minTraceSz==190b so round up to nearest is 256b
  // constant

  require(maxTraceSize < (outDataSzBits / totalStreamsPerToken), "All instruction trace bits must fit in 256b")

  // how many streams being sent over
  val numStreams = traces.size
  // num tokens needed to display full stream
  val numTokenForAll = ((numStreams - 1) / totalStreamsPerToken) + 1

  // tracequeue is full
  val traceQueueFull = outgoingPCISdat.io.deq.valid && !outgoingPCISdat.io.enq.ready

  // only inc the counter when the something is sent... input is valid... and output is avail
  val counterFire = outgoingPCISdat.io.enq.fire() && hPort.toHost.hValid && !traceQueueFull
  val (cnt, wrap) = Counter(counterFire, numTokenForAll)

  val paddedTracesAligned = paddedTraces.map(t => t.asUInt.pad(outDataSzBits/totalStreamsPerToken))
  val paddedTracesTruncated = if (numStreams == 1) {
    (paddedTracesAligned.asUInt >> (outDataSzBits.U * cnt))
  } else {
    (paddedTracesAligned.asUInt >> (outDataSzBits.U * cnt))(outDataSzBits-1, 0)
  }

  outgoingPCISdat.io.enq.valid := hPort.toHost.hValid
  outgoingPCISdat.io.enq.bits := paddedTracesTruncated

  // tell the host that you are ready to get more
  hPort.toHost.hReady := outgoingPCISdat.io.enq.ready && wrap

  // This is uni-directional. We don't drive tokens back to the target.
  hPort.fromHost.hValid := true.B

  // tell c driver that queue is full using MMIO
  attach(traceQueueFull, "trace_queue_full", ReadOnly)

  genCRFile()

  // modify the output header file
  override def genHeader(base: BigInt, sb: StringBuilder) {
    super.genHeader(base, sb)

    sb.append(CppGenerationUtils.genMacro(s"${getWName.toUpperCase}_iaddr_width", UInt32(iaddrWidth)))
    sb.append(CppGenerationUtils.genMacro(s"${getWName.toUpperCase}_insn_width", UInt32(insnWidth)))
    sb.append(CppGenerationUtils.genMacro(s"${getWName.toUpperCase}_wdata_width", UInt32(wdataWidth)))
    sb.append(CppGenerationUtils.genMacro(s"${getWName.toUpperCase}_cause_width", UInt32(causeWidth)))
    sb.append(CppGenerationUtils.genMacro(s"${getWName.toUpperCase}_tval_width", UInt32(tvalWidth)))
    sb.append(CppGenerationUtils.genMacro(s"${getWName.toUpperCase}_num_streams", UInt32(numStreams)))
  }

  // general information printout
  println(s"Dromajo Bridge Information")
  println(s"  Total Inst. Streams: ${numStreams}")
}
