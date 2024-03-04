// See LICENSE for license details.

package midas.core

import chisel3._
import chisel3.util._
import chisel3.experimental.prefix
import freechips.rocketchip.amba.axi4._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util.DecoupledHelper

import midas.targetutils.xdc
import midas.targetutils.{FireSimQueueHelper}
import midas.widgets._

class StreamAdapterIO(val w: Int) extends Bundle {
  val in = Flipped(Decoupled(UInt(w.W)))
  val out = Decoupled(UInt(w.W))

  def flipConnect(other: StreamAdapterIO) {
    in <> other.out
    other.in <> out
  }
}

class StreamWidthAdapter(narrowW: Int, wideW: Int) extends Module {
  require(wideW >= narrowW)
  require(wideW % narrowW == 0)
  val io = IO(new Bundle {
    val narrow = new StreamAdapterIO(narrowW)
    val wide = new StreamAdapterIO(wideW)
  })

  if (wideW == narrowW) {
    io.narrow.out <> io.wide.in
    io.wide.out <> io.narrow.in
  } else {
    val beats = wideW / narrowW

    val narrow_beats = RegInit(0.U(log2Ceil(beats).W))
    val narrow_last_beat = narrow_beats === (beats-1).U
    val narrow_data = Reg(Vec(beats-1, UInt(narrowW.W)))

    val wide_beats = RegInit(0.U(log2Ceil(beats).W))
    val wide_last_beat = wide_beats === (beats-1).U

    io.narrow.in.ready := Mux(narrow_last_beat, io.wide.out.ready, true.B)
    when (io.narrow.in.fire()) {
      narrow_beats := Mux(narrow_last_beat, 0.U, narrow_beats + 1.U)
      when (!narrow_last_beat) { narrow_data(narrow_beats) := io.narrow.in.bits }
    }
    io.wide.out.valid := narrow_last_beat && io.narrow.in.valid
    io.wide.out.bits := Cat(io.narrow.in.bits, narrow_data.asUInt)

    io.narrow.out.valid := io.wide.in.valid
    io.narrow.out.bits := io.wide.in.bits.asTypeOf(Vec(beats, UInt(narrowW.W)))(wide_beats)
    when (io.narrow.out.fire()) {
      wide_beats := Mux(wide_last_beat, 0.U, wide_beats + 1.U)
    }
    io.wide.in.ready := wide_last_beat && io.narrow.out.ready
  }
}

/**
  * A helper container to serialize per-stream constants to the header. This is
  * currently somewhat redundant with the default header emission for widgets.
  */
case class StreamDriverParameters(
  name: String,
  bufferBaseAddress: Int,
  countMMIOAddress: Int,
  bufferCapacity: Int,
  bufferWidthBytes: Int)

class CPUManagedStreamEngine(p: Parameters, val params: StreamEngineParameters) extends StreamEngine(p) {

  val cpuManagedAXI4params = p(CPUManagedAXI4Key).get
  require(BridgeStreamConstants.streamWidthBits >= cpuManagedAXI4params.dataBits,
    s"CPU-managed AXI4 IF data width (${cpuManagedAXI4params.dataBits}) must be less than or equal to the stream width (${BridgeStreamConstants.streamWidthBits}).")

  val axiBeatBytes = cpuManagedAXI4params.dataBits / 8
  val bufferWidthBytes = BridgeStreamConstants.streamWidthBits / 8

  val cpuManagedAXI4NodeOpt = Some(AXI4SlaveNode(
      Seq(AXI4SlavePortParameters(
        slaves = Seq(AXI4SlaveParameters(
          address       = Seq(AddressSet(0, (BigInt(1) << cpuManagedAXI4params.addrBits) - 1)),
          resources     = (new MemoryDevice).reg,
          regionType    = RegionType.UNCACHED, // cacheable
          executable    = false,
          supportsWrite = TransferSizes(axiBeatBytes, 4096),
          supportsRead  = TransferSizes(axiBeatBytes, 4096),
          interleavedId = Some(0))), // slave does not interleave read responses
        beatBytes = axiBeatBytes)
    ))
  )


  val fpgaManagedAXI4NodeOpt = None

  lazy val module = new WidgetImp(this) {
    val io = IO(new WidgetIO)

    val axi4 = cpuManagedAXI4NodeOpt.get.in.head._1

    // FromHostCPU streams are implemented using the AW, W, B channels, which
    // write into large BRAM FIFOs for each stream.
    assert(!axi4.aw.valid || axi4.aw.bits.size === log2Ceil(axiBeatBytes).U)
    assert(!axi4.w.valid  || axi4.w.bits.strb === ~0.U(axiBeatBytes.W))

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

      val ser_des = Module(new StreamWidthAdapter(cpuManagedAXI4params.dataBits, BridgeStreamConstants.streamWidthBits))
      // unused
      ser_des.io.wide.in.bits := 0.U
      ser_des.io.wide.in.valid := false.B
      ser_des.io.narrow.out.ready := false.B

      val streamName = chParams.name
      val grant = (axi4.aw.bits.addr >> addressSpaceBits) === idx.U

      val incomingQueueIO = FireSimQueueHelper.makeIO(UInt(BridgeStreamConstants.streamWidthBits.W), chParams.fpgaBufferDepth, isFireSim=true, overrideStyle=Some(xdc.RAMStyles.ULTRA))

      channel <> incomingQueueIO.deq

      // check to see if axi4 is ready to accept data instead of forcing writes
      val countAddr =
        attach(incomingQueueIO.count, s"${chParams.name}_count", ReadOnly, substruct = false)

      incomingQueueIO.enq.bits := ser_des.io.wide.out.bits
      incomingQueueIO.enq.valid := ser_des.io.wide.out.valid
      ser_des.io.wide.out.ready := incomingQueueIO.enq.ready

      val writeHelper = DecoupledHelper(
        axi4.aw.valid,
        axi4.w.valid,
        axi4.b.ready,
        ser_des.io.narrow.in.ready
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

      ser_des.io.narrow.in.valid := grant && writeHelper.fire(ser_des.io.narrow.in.ready)
      ser_des.io.narrow.in.bits := axi4.w.bits.data

      StreamDriverParameters(
        chParams.name,
        idx * (1 << addressSpaceBits),
        countAddr,
        chParams.fpgaBufferDepth,
        chParams.fpgaBufferWidthBytes
        )
    }

    assert(!axi4.ar.valid || axi4.ar.bits.size === log2Ceil(axiBeatBytes).U)

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


      val ser_des = Module(new StreamWidthAdapter(cpuManagedAXI4params.dataBits, BridgeStreamConstants.streamWidthBits))
      // unused
      ser_des.io.narrow.in.bits := 0.U
      ser_des.io.narrow.in.valid := false.B
      ser_des.io.wide.out.ready := false.B

      val grant = (axi4.ar.bits.addr >> addressSpaceBits) === idx.U

      val outgoingQueueIO = FireSimQueueHelper.makeIO(UInt(BridgeStreamConstants.streamWidthBits.W), chParams.fpgaBufferDepth, isFireSim=true, overrideStyle=Some(xdc.RAMStyles.ULTRA))

      outgoingQueueIO.enq <> channel

      ser_des.io.wide.in.bits := outgoingQueueIO.deq.bits
      ser_des.io.wide.in.valid := outgoingQueueIO.deq.valid
      outgoingQueueIO.deq.ready := ser_des.io.wide.in.ready

      // check to see if axi4 has valid output instead of waiting for timeouts
      val countAddr =
        attach(outgoingQueueIO.count, s"${chParams.name}_count", ReadOnly, substruct = false)

      val readHelper = DecoupledHelper(
        axi4.ar.valid,
        axi4.r.ready,
        ser_des.io.narrow.out.valid
      )

      val readBeatCounter = RegInit(0.U(9.W))
      val lastReadBeat = readBeatCounter === axi4.ar.bits.len
      when (axi4.r.fire) {
        readBeatCounter := Mux(lastReadBeat, 0.U, readBeatCounter + 1.U)
      }

      ser_des.io.narrow.out.ready := grant && readHelper.fire(ser_des.io.narrow.out.valid)

      when (grant) {
        axi4.r.valid := readHelper.fire(axi4.r.ready)
        axi4.r.bits.data := ser_des.io.narrow.out.bits
        axi4.r.bits.last := lastReadBeat
        axi4.ar.ready := readHelper.fire(axi4.ar.valid, lastReadBeat)
      }
      StreamDriverParameters(
        chParams.name,
        idx * (1 << addressSpaceBits),
        countAddr,
        chParams.fpgaBufferDepth,
        chParams.fpgaBufferWidthBytes)
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
      def streamASBits = log2Ceil(bufferWidthBytes * streamParameters.map(_.fpgaBufferDepth).max)

      for (((port, params), idx) <- streamPorts.zip(streamParameters).zipWithIndex) yield {
        elaborator(port, params, idx, streamASBits)
      }
    }

    val sourceDriverParameters = implementStreams(sourceParams, streamsToHostCPU,   elaborateToHostCPUStream).toSeq
    val sinkDriverParameters   = implementStreams(sinkParams,   streamsFromHostCPU, elaborateFromHostCPUStream).toSeq

    genCRFile()

    override def genHeader(base: BigInt, memoryRegions: Map[String, BigInt], sb: StringBuilder): Unit = {
      def serializeStreamParameters(params: Seq[StreamDriverParameters]): StdVector = {
        StdVector("CPUManagedStreams::StreamParameters", params.map(p =>
          Verbatim(s"""|CPUManagedStreams::StreamParameters(
                       |  std::string(${CStrLit(p.name).toC}),
                       |  ${UInt64(p.bufferBaseAddress).toC},
                       |  ${UInt64(base + p.countMMIOAddress).toC},
                       |  ${UInt32(p.bufferCapacity).toC},
                       |  ${UInt32(p.bufferWidthBytes).toC}
                       |)""".stripMargin)))
      }

      genConstructor(
          base,
          sb,
          "CPUManagedStreamWidget",
          "cpu_managed_stream",
          Seq(
            serializeStreamParameters(sinkDriverParameters),
            serializeStreamParameters(sourceDriverParameters)
          ),
          "GET_MANAGED_STREAM_CONSTRUCTOR"
      )
    }
  }
}
