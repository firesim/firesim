package firesim
package endpoints

import chisel3.core._
import chisel3.util._
import chisel3.Module
import DataMirror.directionOf
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.util._
import freechips.rocketchip.subsystem.RocketTilesKey
import freechips.rocketchip.tile.TileKey


import midas.core._
import midas.widgets._
import testchipip.{StreamIO, StreamChannel}
import icenet.{NICIOvonly, RateLimiterSettings}
import icenet.IceNIC._
import junctions.{NastiIO, NastiKey}
import freechips.rocketchip.rocket.TracedInstruction

class TraceOutputTop(val numTraces: Int)(implicit val p: Parameters) extends Bundle {
  val traces = Vec(numTraces, new TracedInstruction)
}

class SimTracerV extends Endpoint {

  // this is questionable ...
  // but I can't see a better way to do this for now. getting sharedMemoryTLEdge is the problem.
  var tracer_param = Parameters.empty
  def matchType(data: Data) = data match {
    case channel: TraceOutputTop => {
      // this is questionable ...
      tracer_param = channel.traces(0).p
      true  
    }
/*      directionOf(channel.out.valid) == ActualDirection.Output*/
    case _ => false
  }
  def widget(p: Parameters) = new TracerVWidget(tracer_param)(p)
  override def widgetName = "TracerVWidget"
}

class TracerVWidgetIO(tracerParams: Parameters)(implicit p: Parameters) extends EndpointWidgetIO()(p) {
  val hPort = Flipped(HostPort(new TraceOutputTop(1)(tracerParams)))
  val dma = Some(Flipped(new NastiIO()(
      p.alterPartial({ case NastiKey => p(DMANastiKey) }))))
}


class TracerVWidget(tracerParams: Parameters)(implicit p: Parameters) extends EndpointWidget()(p) {
  val io = IO(new TracerVWidgetIO(tracerParams))

   // copy from FireSim's SimpleNICWidget, because it should work here too
  val outgoingPCISdat = Module(new SplitSeqQueue)
  val PCIS_BYTES = 64
   outgoingPCISdat.io.enq.bits := io.hPort.hBits.traces(0).asUInt
   // and io.dma gets you access to pcis
  io.dma.map { dma =>
    // copy from FireSim's SimpleNICWidget, because it should work here too
    val ar_queue = Queue(dma.ar, 10)
     assert(!ar_queue.valid || ar_queue.bits.size === log2Ceil(PCIS_BYTES).U)
     def fire_read(exclude: Bool, includes: Bool*) = {
      val rvs = Array (
        ar_queue.valid,
        dma.r.ready,
        outgoingPCISdat.io.deq.valid
      )
      (rvs.filter(_ ne exclude) ++ includes).reduce(_ && _)
    }
    val readBeatCounter = RegInit(0.U(9.W))
    when (fire_read(true.B, readBeatCounter === ar_queue.bits.len)) {
      //printf("resetting readBeatCounter\n")
      readBeatCounter := 0.U
    } .elsewhen(fire_read(true.B)) {
      //printf("incrementing readBeatCounter\n")
      readBeatCounter := readBeatCounter + 1.U
    } .otherwise {
      readBeatCounter := readBeatCounter
    }
     outgoingPCISdat.io.deq.ready := fire_read(outgoingPCISdat.io.deq.valid)
     dma.r.valid := fire_read(dma.r.ready)
    dma.r.bits.data := outgoingPCISdat.io.deq.bits
    dma.r.bits.resp := 0.U(2.W)
    dma.r.bits.last := readBeatCounter === ar_queue.bits.len
    dma.r.bits.id := ar_queue.bits.id
    dma.r.bits.user := ar_queue.bits.user
    ar_queue.ready := fire_read(ar_queue.valid, readBeatCounter === ar_queue.bits.len)
     // we don't care about writes
    dma.aw.ready := 0.U
    dma.w.ready := 0.U
    dma.b.valid := 0.U
    dma.b.bits.resp := 0.U(2.W)
    dma.b.bits.id := 0.U
    dma.b.bits.user := 0.U
  }

  val tFireHelper = DecoupledHelper(outgoingPCISdat.io.enq.ready,
    io.hPort.toHost.hValid, io.hPort.fromHost.hReady, io.tReset.valid)

  io.tReset.ready := tFireHelper.fire(io.tReset.valid)
  io.hPort.fromHost.hValid := tFireHelper.fire(io.hPort.fromHost.hReady)
  io.hPort.toHost.hReady := tFireHelper.fire(io.hPort.toHost.hValid)

  outgoingPCISdat.io.enq.valid := tFireHelper.fire(outgoingPCISdat.io.enq.ready)


//class TracedInstruction(implicit p: Parameters) extends CoreBundle {
//  val valid = Bool()
//  val iaddr = UInt(width = coreMaxAddrBits)
//  val insn = UInt(width = iLen)
//  val priv = UInt(width = 3)
//  val exception = Bool()
//  val interrupt = Bool()
//  val cause = UInt(width = log2Ceil(1 + CSR.busErrorIntCause))
//  val tval = UInt(width = coreMaxAddrBits max iLen)
//}
//
//
//

   when (tFireHelper.fire(true.B)) {
    printf("valid: %x\n", io.hPort.hBits.traces(0).valid)
    printf("iaddr: %x\n", io.hPort.hBits.traces(0).iaddr)
    printf("insn: %x\n", io.hPort.hBits.traces(0).insn)
    printf("priv: %x\n", io.hPort.hBits.traces(0).priv)
    printf("exception: %x\n", io.hPort.hBits.traces(0).exception)
    printf("interrupt: %x\n", io.hPort.hBits.traces(0).interrupt)
    printf("cause: %x\n", io.hPort.hBits.traces(0).cause)
    printf("tval: %x\n", io.hPort.hBits.traces(0).tval)


//    printf("%d\n", model.io.cmdTrace.bank)
//    printf("%d\n", model.io.cmdTrace.autoPRE)
  }
  attach(outgoingPCISdat.io.deq.valid && !outgoingPCISdat.io.enq.ready, "tracequeuefull", ReadOnly)
  genCRFile()
}
