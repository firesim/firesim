
// See LICENSE for license details.

package midas.core

import chisel3._
import chisel3.util._
import chisel3.experimental.prefix
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.config.Parameters
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
  name: String,
  bufferBaseAddress: Int,
  countMMIOAddress: Int,
  bufferCapacity: Int)

class CPUManagedStreamEngine(p: Parameters, val params: StreamEngineParameters) extends StreamEngine(p) {

  val cpuManagedAXI4params = p(CPUManagedAXI4Key).get
  require(BridgeStreamConstants.streamWidthBits == cpuManagedAXI4params.dataBits,
    s"CPU-managed AXI4 IF data width must match the stream width: ${BridgeStreamConstants.streamWidthBits}.")

  val beatBytes = cpuManagedAXI4params.dataBits / 8

  val cpuManagedAXI4NodeOpt = Some(AXI4SlaveNode(
      Seq(AXI4SlavePortParameters(
        slaves = Seq(AXI4SlaveParameters(
          address       = Seq(AddressSet(0, (BigInt(1) << cpuManagedAXI4params.addrBits) - 1)),
          resources     = (new MemoryDevice).reg,
          regionType    = RegionType.UNCACHED, // cacheable
          executable    = false,
          supportsWrite = TransferSizes(beatBytes, 4096),
          supportsRead  = TransferSizes(beatBytes, 4096),
          interleavedId = Some(0))), // slave does not interleave read responses
        beatBytes = beatBytes)
    ))
  )


  val fpgaManagedAXI4NodeOpt = None

  lazy val module = new WidgetImp(this) {
    val io = IO(new WidgetIO)

    val axi4 = cpuManagedAXI4NodeOpt.get.in.head._1

    // FromHostCPU streams are implemented using the AW, W, B channels, which
    // write into large BRAM FIFOs for each stream.
    assert(!axi4.aw.valid || axi4.aw.bits.size === log2Ceil(beatBytes).U)
    assert(!axi4.w.valid  || axi4.w.bits.strb === ~0.U(beatBytes.W))

    axi4.b.bits.resp := 0.U(2.W)
    axi4.b.bits.id := axi4.aw.bits.id
    axi4.b.bits.user := axi4.aw.bits.user
    // This will be set by the channel given the grant using last connect semantics
    axi4.b.valid := false.B
    axi4.aw.ready := false.B
    axi4.w.ready := false.B


    // TODO: Chisel naming prefix to indicate what channel this hw belongs to.
    // This demultiplexes the AW, W, and B channels onto the decoupled ports representing each stream.
    def elaborateFromHostCPUStream(
        channel: DecoupledIO[UInt],
        chParams: StreamSinkParameters,
        idx: Int,
        addressSpaceBits: Int): StreamDriverParameters = prefix(chParams.name) {

      val streamName = chParams.name
      val grant = (axi4.aw.bits.addr >> addressSpaceBits) === idx.U

      val incomingQueue = Module(new BRAMQueue(chParams.fpgaBufferDepth)(UInt(BridgeStreamConstants.streamWidthBits.W)))
      xdc.RAMStyleHint(incomingQueue.fq.ram, xdc.RAMStyles.ULTRA)

      channel <> incomingQueue.io.deq

      // check to see if axi4 is ready to accept data instead of forcing writes
      val countAddr =
        attach(incomingQueue.io.count, s"${chParams.name}_count", ReadOnly)

      val writeHelper = DecoupledHelper(
        axi4.aw.valid,
        axi4.w.valid,
        axi4.b.ready,
        incomingQueue.io.enq.ready
      )

      // TODO: Get rid of this magic number.
      val writeBeatCounter = RegInit(0.U(9.W))
      val lastWriteBeat = writeBeatCounter === axi4.aw.bits.len
      when (grant && axi4.w.fire) {
        writeBeatCounter := Mux(lastWriteBeat, 0.U, writeBeatCounter + 1.U)
      }

      when (grant) {
        axi4.w.ready  := writeHelper.fire(axi4.w.valid)
        axi4.aw.ready := writeHelper.fire(axi4.aw.valid, lastWriteBeat)
        axi4.b.valid  := writeHelper.fire(axi4.b.ready, lastWriteBeat)
      }

      incomingQueue.io.enq.valid := grant && writeHelper.fire(incomingQueue.io.enq.ready)
      incomingQueue.io.enq.bits := axi4.w.bits.data

      StreamDriverParameters(
        chParams.name,
        idx * (1 << addressSpaceBits),
        countAddr,
        chParams.fpgaBufferDepth
        )
    }

    assert(!axi4.ar.valid || axi4.ar.bits.size === log2Ceil(beatBytes).U)

    axi4.r.bits.resp := 0.U(2.W)
    axi4.r.bits.id := axi4.ar.bits.id
    axi4.r.bits.user := axi4.ar.bits.user
    axi4.r.valid  := false.B
    axi4.ar.ready := false.B

    // This demultiplexes the AW, W, and B channels onto the decoupled ports representing each stream.
    def elaborateToHostCPUStream(
        channel: DecoupledIO[UInt],
        chParams: StreamSourceParameters,
        idx: Int,
        addressSpaceBits: Int): StreamDriverParameters = prefix(chParams.name) {

      val grant = (axi4.ar.bits.addr >> addressSpaceBits) === idx.U

      val outgoingQueue = Module(new BRAMQueue(chParams.fpgaBufferDepth)(UInt(BridgeStreamConstants.streamWidthBits.W)))
      xdc.RAMStyleHint(outgoingQueue.fq.ram, xdc.RAMStyles.ULTRA)

      outgoingQueue.io.enq <> channel

      // check to see if axi4 has valid output instead of waiting for timeouts
      val countAddr =
        attach(outgoingQueue.io.count, s"${chParams.name}_count", ReadOnly)

      val readHelper = DecoupledHelper(
        axi4.ar.valid,
        axi4.r.ready,
        outgoingQueue.io.deq.valid
      )

      val readBeatCounter = RegInit(0.U(9.W))
      val lastReadBeat = readBeatCounter === axi4.ar.bits.len
      when (axi4.r.fire) {
        readBeatCounter := Mux(lastReadBeat, 0.U, readBeatCounter + 1.U)
      }

      outgoingQueue.io.deq.ready := grant && readHelper.fire(outgoingQueue.io.deq.valid)

      when (grant) {
        axi4.r.valid := readHelper.fire(axi4.r.ready)
        axi4.r.bits.data := outgoingQueue.io.deq.bits
        axi4.r.bits.last := lastReadBeat
        axi4.ar.ready := readHelper.fire(axi4.ar.valid, lastReadBeat)
      }
      StreamDriverParameters(
        chParams.name,
        idx * (1 << addressSpaceBits),
        countAddr,
        chParams.fpgaBufferDepth)
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
      // fractured into multiple, smaller AXI4 transactions (<= 4K in size), it
      // is simplest to maintain the illusion that each stream is granted an
      // address range at least as large as the largest DMA access.
      def streamASBits = log2Ceil(beatBytes * streamParameters.map(_.fpgaBufferDepth).max)

      for (((port, params), idx) <- streamPorts.zip(streamParameters).zipWithIndex) yield {
        elaborator(port, params, idx, streamASBits)
      }
    }

    val sourceDriverParameters = implementStreams(sourceParams, streamsToHostCPU,   elaborateToHostCPUStream).toSeq
    val sinkDriverParameters   = implementStreams(sinkParams,   streamsFromHostCPU, elaborateFromHostCPUStream).toSeq

    genCRFile()

    override def genHeader(base: BigInt, memoryRegions: Map[String, BigInt], sb: StringBuilder) {
      val headerWidgetName = getWName.toUpperCase
      super.genHeader(base, memoryRegions, sb)

      def serializeStreamParameters(prefix: String, params: Seq[StreamDriverParameters]): Unit = {
        val numStreams = params.size
        sb.append(genConstStatic(s"${headerWidgetName}_${prefix}_stream_count", UInt32(numStreams)))

        // Hack: avoid emitting a zero-sized array by providing a dummy set of
        // parameters when no streams are generated. This is a limitation of the
        // current C emission strategy. Note, the actual number of streams is still reported above.
        val placeholder = StreamDriverParameters("UNUSED", 0, 0, 0)
        val nonEmptyParams = if (numStreams == 0) Seq(placeholder) else params

        val arraysToEmit = Seq(
          "names"         -> nonEmptyParams.map { p => CStrLit(p.name) },
          "dma_addrs"     -> nonEmptyParams.map { p => UInt64(p.bufferBaseAddress) },
          "count_addrs"   -> nonEmptyParams.map { p => UInt64(base + p.countMMIOAddress) },
          "buffer_sizes"  -> nonEmptyParams.map { p => UInt32(p.bufferCapacity) },
        )

        for ((name, values) <- arraysToEmit) {
          sb.append(genArray(s"${headerWidgetName}_${prefix}_${name}", values))
        }
      }

      serializeStreamParameters("to_cpu",   sourceDriverParameters)
      serializeStreamParameters("from_cpu", sinkDriverParameters)
    }
  }
}
