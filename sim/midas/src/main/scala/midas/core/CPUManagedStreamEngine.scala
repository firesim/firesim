
// See LICENSE for license details.

package midas.core

import chisel3._
import chisel3.util._
import chisel3.experimental.{IO, prefix}
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util.DecoupledHelper

import midas.targetutils.xdc
import midas.widgets._
import midas.widgets.CppGenerationUtils._

/**
  * A helper container to serialize per-stream constants to the header. This is
  * currently somewhat redundant with the default header emission for widgets.
  */
case class StreamDriverParameters(
  channelName: String,
  bufferBaseAddress: Int,
  mmioRegisters: (String, Int)*) {

  def headerFragment(mmioBaseAddress: BigInt): String = {
    val registerMacros = for ((name, offset) <- mmioRegisters) yield {
      genConstStatic(s"${channelName}_${name}_address", UInt32(mmioBaseAddress + offset))
    }
    val dmaAddressMacro = genConstStatic(s"${channelName}_dma_address", UInt32(bufferBaseAddress))

    (dmaAddressMacro +: registerMacros.toSeq).mkString
  }
}

class CPUManagedStreamEngine(p: Parameters, val params: StreamEngineParameters) extends StreamEngine(p) {

  val dmaBytes = p(DMANastiKey).dataBits / 8
  val pcisNodeOpt = Some(AXI4SlaveNode(
      Seq(AXI4SlavePortParameters(
        slaves = Seq(AXI4SlaveParameters(
          address       = Seq(AddressSet(0, (BigInt(1) << p(DMANastiKey).dataBits) - 1)),
          resources     = (new MemoryDevice).reg,
          regionType    = RegionType.UNCACHED, // cacheable
          executable    = false,
          supportsWrite = TransferSizes(dmaBytes, 4096),
          supportsRead  = TransferSizes(dmaBytes, 4096),
          interleavedId = Some(0))), // slave does not interleave read responses
        beatBytes = dmaBytes)
    ))
  )

  //require(BridgeStreamConstants.streamWidthBits == p(DMANastiKey).dataBits,
  //  s"CPU-mastered AXI4 IF data width must match the stream width ${BridgeStreamConstants.streamWidthBits}".)

  val pcimNodeOpt = None

  lazy val module = new WidgetImp(this) {
    val io = IO(new WidgetIO)

    val dma = pcisNodeOpt.get.in.head._1

    // FromHostCPU streams are implemented using the AW, W, B channels, which
    // write into large BRAM FIFOs for each stream.
    assert(!dma.aw.valid || dma.aw.bits.size === log2Ceil(dmaBytes).U)
    assert(!dma.w.valid  || dma.w.bits.strb === ~0.U(dmaBytes.W))

    dma.b.bits.resp := 0.U(2.W)
    dma.b.bits.id := dma.aw.bits.id
    dma.b.bits.user := dma.aw.bits.user
    // This will be set by the channel given the grant using last connect semantics
    dma.b.valid := false.B
    dma.aw.ready := false.B
    dma.w.ready := false.B


    // TODO: Chisel naming prefix to indicate what channel this hw belongs to.
    // This demultiplexes the AW, W, and B channels onto the decoupled ports representing each stream.
    def elaborateFromHostCPUStream(
        channel: DecoupledIO[UInt],
        chParams: StreamSinkParameters,
        idx: Int,
        addressSpaceBits: Int): StreamDriverParameters = prefix(chParams.name) {

      val streamName = chParams.name
      val grant = (dma.aw.bits.addr >> addressSpaceBits) === idx.U

      val incomingQueue = Module(new BRAMQueue(chParams.fpgaBufferDepth)(UInt(BridgeStreamConstants.streamWidthBits.W)))
      xdc.RAMStyleHint(incomingQueue.fq.ram, xdc.RAMStyles.ULTRA)

      channel <> incomingQueue.io.deq

      // check to see if pcis is ready to accept data instead of forcing writes
      val countAddr =
        attach(incomingQueue.io.count, s"${chParams.name}_count", ReadOnly)

      val writeHelper = DecoupledHelper(
        dma.aw.valid,
        dma.w.valid,
        dma.b.ready,
        incomingQueue.io.enq.ready
      )

      // TODO: Get rid of this magic number.
      val writeBeatCounter = RegInit(0.U(9.W))
      val lastWriteBeat = writeBeatCounter === dma.aw.bits.len
      when (grant && dma.w.fire) {
        writeBeatCounter := Mux(lastWriteBeat, 0.U, writeBeatCounter + 1.U)
      }

      when (grant) {
        dma.w.ready  := writeHelper.fire(dma.w.valid)
        dma.aw.ready := writeHelper.fire(dma.aw.valid, lastWriteBeat)
        dma.b.valid  := writeHelper.fire(dma.b.ready, lastWriteBeat)
      }

      incomingQueue.io.enq.valid := grant && writeHelper.fire(incomingQueue.io.enq.ready)
      incomingQueue.io.enq.bits := dma.w.bits.data

      StreamDriverParameters(
        chParams.name,
        idx * (1 << addressSpaceBits),
        "count" -> countAddr)
    }

    assert(!dma.ar.valid || dma.ar.bits.size === log2Ceil(dmaBytes).U)

    dma.r.bits.resp := 0.U(2.W)
    dma.r.bits.id := dma.ar.bits.id
    dma.r.bits.user := dma.ar.bits.user
    dma.r.valid  := false.B
    dma.ar.ready := false.B

    // This demultiplexes the AW, W, and B channels onto the decoupled ports representing each stream.
    def elaborateToHostCPUStream(
        channel: DecoupledIO[UInt],
        chParams: StreamSourceParameters,
        idx: Int,
        addressSpaceBits: Int): StreamDriverParameters = prefix(chParams.name) {

      val grant = (dma.ar.bits.addr >> addressSpaceBits) === idx.U

      val outgoingQueue = Module(new BRAMQueue(chParams.fpgaBufferDepth)(UInt(BridgeStreamConstants.streamWidthBits.W)))
      xdc.RAMStyleHint(outgoingQueue.fq.ram, xdc.RAMStyles.ULTRA)

      outgoingQueue.io.enq <> channel

      // check to see if pcis has valid output instead of waiting for timeouts
      val countAddr =
        attach(outgoingQueue.io.count, s"${chParams.name}_count", ReadOnly)
      val fullAddr =
        attach(outgoingQueue.io.deq.valid && !outgoingQueue.io.enq.ready, s"${chParams.name}_full", ReadOnly)

      val readHelper = DecoupledHelper(
        dma.ar.valid,
        dma.r.ready,
        outgoingQueue.io.deq.valid
      )

      val readBeatCounter = RegInit(0.U(9.W))
      val lastReadBeat = readBeatCounter === dma.ar.bits.len
      when (dma.r.fire) {
        readBeatCounter := Mux(lastReadBeat, 0.U, readBeatCounter + 1.U)
      }

      outgoingQueue.io.deq.ready := grant && readHelper.fire(outgoingQueue.io.deq.valid)

      when (grant) {
        dma.r.valid := readHelper.fire(dma.r.ready)
        dma.r.bits.data := outgoingQueue.io.deq.bits
        dma.r.bits.last := lastReadBeat
        dma.ar.ready := readHelper.fire(dma.ar.valid, lastReadBeat)
      }
      StreamDriverParameters(
        chParams.name,
        idx * (1 << addressSpaceBits),
        "count" -> countAddr,
        "full"  -> fullAddr)
    }

    def implementStreams[A <: StreamParameters](
        streamParameters: Iterable[A],
        streamPorts: Iterable[DecoupledIO[UInt]],
        elaborator: (DecoupledIO[UInt], A, Int, Int) => StreamDriverParameters): Iterable[StreamDriverParameters] = {

      assert(streamParameters.size == streamPorts.size)

      // Address allocation. Really, the behavior implementation here ignores burst
      // type field (which is hardwired to INCR on some systems), and implements the "FIXED"
      // burst type (which is semantically consistent with draining or filling a queue).
      //
      // However, since large DMA transactions initiated by the driver are
      // fractured into multiple, smaller AXI4 transactions on the PCIS
      // interface*, it is simplest to maintain the illusion that each stream is
      // granted an address range at least as large as the largest DMA access.
      //
      // * On EC2 F1, and likely all XDMA-based systems, requests larger than a
      // 4K page are fractured into 4K or smaller transactions.
      // treats them as "FIXED" type bursts
      def streamASBits = log2Ceil(dmaBytes * streamParameters.map(_.fpgaBufferDepth).max)

      for (((port, params), idx) <- streamPorts.zip(streamParameters).zipWithIndex) yield {
        elaborator(port, params, idx, streamASBits)
      }
    }

    val sourceDriverParameters = implementStreams(sourceParams, streamsToHostCPU,   elaborateToHostCPUStream)
    val sinkDriverParameters   = implementStreams(sinkParams,   streamsFromHostCPU, elaborateFromHostCPUStream)

    genCRFile()

    override def genHeader(base: BigInt, sb: StringBuilder) {
      sourceDriverParameters.foreach { params => sb.append(params.headerFragment(base)) }
      sinkDriverParameters.foreach { params => sb.append(params.headerFragment(base)) }
    }
  }
}
