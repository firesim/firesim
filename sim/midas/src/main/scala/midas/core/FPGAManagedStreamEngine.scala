// See LICENSE for license details.

package midas.core

import chisel3._
import chisel3.util._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.diplomacy._

import midas.widgets._
import midas.widgets.CppGenerationUtils._

class WriteMetadata(val numBeatsWidth: Int) extends Bundle {
  val numBeats = Output(UInt(numBeatsWidth.W))
  val isFlush  = Output(Bool())
}

class FPGAManagedStreamEngine(p: Parameters, val params: StreamEngineParameters) extends StreamEngine(p) {
  require(sinkParams.isEmpty, "FPGAManagedStreamEngine does not currently support FPGA-sunk streams.")

  // Beats refers to 512b words moving over a stream
  val pageBytes = 4096
  val beatBytes = BridgeStreamConstants.streamWidthBits / 8
  val pageBeats = pageBytes / beatBytes

  def maxFlightForStream(params: StreamSourceParameters): Int =
    (params.fpgaBufferDepth * beatBytes) / pageBytes

  val cpuManagedAXI4NodeOpt = None

  val (fpgaManagedAXI4NodeOpt, toCPUNode) = if (hasStreams) {
    // The implicit val defined in StreamEngine is not accessible here; Make a
    // duplicate that can be referenced by diplomatic nodes
    implicit val pShadow = p
    val xbar             = AXI4Xbar()
    val toCPUNode        = AXI4MasterNode(
      sourceParams.map { p =>
        AXI4MasterPortParameters(Seq(AXI4MasterParameters(name = p.name, maxFlight = Some(maxFlightForStream(p)))))
      }
    )
    xbar :=* AXI4Buffer() :=* toCPUNode
    (Some(xbar), Some(toCPUNode))
  } else {
    (None, None)
  }

  lazy val module = new WidgetImp(this) {
    val io = IO(new WidgetIO)

    case class ToCPUStreamDriverParameters(
      name:                      String,
      fpgaBufferDepth:           Int,
      toHostPhysAddrHighAddr:    Int,
      toHostPhysAddrLowAddr:     Int,
      bytesAvailableAddr:        Int,
      bytesConsumedAddr:         Int,
      toHostStreamDoneInitAddr:  Int,
      toHostStreamFlushAddr:     Int,
      toHostStreamFlushDoneAddr: Int,
    )

    // Invoke this in the module implementation
    def elaborateToHostCPUStream(
      channel:  DecoupledIO[UInt],
      axi4:     AXI4Bundle,
      chParams: StreamSourceParameters,
    ): ToCPUStreamDriverParameters = {

      require(
        BridgeStreamConstants.streamWidthBits == axi4.params.dataBits,
        s"FPGAManagedStreamEngine requires stream widths to match FPGA-managed AXI4 data width",
      )
      val cpuBufferDepthBeats = chParams.fpgaBufferDepth
      require(cpuBufferDepthBeats > pageBeats)
      val cpuBufferSizeBytes  = (1 << log2Ceil(cpuBufferDepthBeats)) * (BridgeStreamConstants.streamWidthBits / 8)
      // This to simplify the hardware
      require(isPow2(cpuBufferSizeBytes))

      val toHostPhysAddrHigh = Reg(UInt(32.W))
      val toHostPhysAddrLow  = Reg(UInt(32.W))
      val bytesConsumedByCPU = RegInit(0.U(log2Ceil(cpuBufferSizeBytes + 1).W))

      // This sets up a double buffer that should give full throughput for a
      // single stream system. This queue could be grown under a multi-stream system.
      val outgoingQueue = Module(new BRAMQueue(2 * pageBeats)(UInt(BridgeStreamConstants.streamWidthBits.W)))
      outgoingQueue.io.enq <> channel

      val writeCredits       = RegInit(cpuBufferSizeBytes.U(log2Ceil(cpuBufferSizeBytes + 1).W))
      val readCredits        = RegInit(0.U(log2Ceil(cpuBufferSizeBytes + 1).W))
      val writePtr           = RegInit(0.U(log2Ceil(cpuBufferSizeBytes).W))
      val doneInit           = RegInit(false.B)
      // Key assumption: write acknowledgements can be used as a synchronization
      // point, after which the CPU can read new data written into its circular
      // buffer. This tracks inflight requests, to increment read credits on
      // write acknowledgement, and to cap maxflight.
      val inflightBeatCounts = Module(
        new Queue(new WriteMetadata(log2Ceil(pageBeats + 1)), maxFlightForStream(chParams))
      )

      val idle :: sendAddress :: sendData :: Nil = Enum(3)
      val state                                  = RegInit(idle)
      val beatsToSendMinus1                      = RegInit(0.U(log2Ceil(pageBeats).W))

      // Ensure we do not cross page boundaries per AXI4 spec.
      val beatsToPageBoundary =
        pageBeats.U - writePtr(log2Ceil(pageBytes) - 1, log2Ceil(beatBytes))
      assert((beatsToPageBoundary > 0.U) && (beatsToPageBoundary <= (pageBeats.U)))

      // Establish the largest AXI4 write request we can make, by doing a min
      // reduction over the following bounds:
      val writeBounds = Seq(
        outgoingQueue.io.count,                // Beats available for enqueue in local FPGA buffer
        writeCredits >> log2Ceil(beatBytes).U, // Space available in cpu buffer
        beatsToPageBoundary,
      ) // Length to end of page
      // NB: BeatsToPageBoundary covers the end of the circular buffer only because
      // we ensure the buffer size is a multiple of page size

      val writeableBeats       = writeBounds.reduce { (a, b) => Mux(a < b, a, b) }
      val writeableBeatsMinus1 = writeableBeats - 1.U

      // This register resets itself to 0 on cycles it is not set by the host
      // CPU.  If it is non-zero it was written to in the last cycle, and so we
      // know we can update credits.
      assert(
        !doneInit || (!(RegNext(bytesConsumedByCPU) =/= 0.U) || (bytesConsumedByCPU === 0.U)),
        "Back-to-back MMIO accesses, or incorrect toggling on bytesConsumedByCPU",
      )
      when(bytesConsumedByCPU =/= 0.U) {
        bytesConsumedByCPU := 0.U
        writeCredits       := writeCredits + bytesConsumedByCPU
        readCredits        := readCredits - bytesConsumedByCPU
      }

      val doFlush, inFlush                   = RegInit(false.B)
      val flushBeatsToIssue, flushBeatsToAck = RegInit(0.U(log2Ceil(cpuBufferDepthBeats + 1).W))

      assert(readCredits >= bytesConsumedByCPU, "Driver read more bytes than available in circular buffer.")
      assert(
        (writeCredits + bytesConsumedByCPU) <= cpuBufferSizeBytes.U,
        "Driver granted more write credit than physically allowable.",
      )

      switch(state) {
        is(idle) {
          doFlush := false.B
          when(doFlush && !inFlush && (outgoingQueue.io.count > 0.U)) {
            inFlush           := true.B
            flushBeatsToIssue := outgoingQueue.io.count
            flushBeatsToAck   := outgoingQueue.io.count
          }
          val start =
            (inflightBeatCounts.io.enq.ready) &&
              ((flushBeatsToIssue =/= 0.U) || (writeableBeats === beatsToPageBoundary))

          when(start) { state := sendAddress }
        }
        is(sendAddress) {
          when(axi4.aw.fire) {
            state             := sendData
            beatsToSendMinus1 := writeableBeatsMinus1
            writePtr          := writePtr + (writeableBeats * beatBytes.U)
            writeCredits      := writeCredits + bytesConsumedByCPU - (writeableBeats * beatBytes.U)
            flushBeatsToIssue := Mux(flushBeatsToIssue < writeableBeats, 0.U, flushBeatsToIssue - writeableBeats)
          }
        }
        is(sendData) {
          when(axi4.w.fire) {
            state             := Mux(axi4.w.bits.last, idle, sendData)
            beatsToSendMinus1 := beatsToSendMinus1 - 1.U
          }
        }
      }

      axi4.aw.valid      := (state === sendAddress)
      axi4.aw.bits.id    := 0.U
      axi4.aw.bits.addr  := Cat(toHostPhysAddrHigh, toHostPhysAddrLow) + writePtr
      axi4.aw.bits.len   := writeableBeatsMinus1
      axi4.aw.bits.size  := (log2Ceil(beatBytes)).U
      // This is assumed but not exposed by the PCIM interface, and is the
      // default transaction type supported by XDMA-backed AXI4 IFs anyways
      axi4.aw.bits.burst := AXI4Parameters.BURST_INCR
      // This to permit intermediate width adapters, etc, to pack narrower
      // transactions into larger ones, in the event we make this IF narrower than 512b
      axi4.aw.bits.cache := AXI4Parameters.CACHE_MODIFIABLE
      // Assume page-sized transfers for now
      // These fields are unused by F1 PCIM, but pick reasonable default values for future proofing
      axi4.aw.bits.prot  := 0.U // Unpriviledged, secure, data access
      axi4.aw.bits.qos   := 0.U // Default; unused
      axi4.aw.bits.lock  := 0.U // Normal, non-exclusive

      inflightBeatCounts.io.enq.valid         := axi4.aw.fire
      inflightBeatCounts.io.enq.bits.numBeats := writeableBeats
      inflightBeatCounts.io.enq.bits.isFlush  := flushBeatsToIssue =/= 0.U

      axi4.w.valid               := (state === sendData) && outgoingQueue.io.deq.valid
      axi4.w.bits.data           := outgoingQueue.io.deq.bits
      axi4.w.bits.strb           := ((BigInt(1) << beatBytes) - 1).U
      axi4.w.bits.last           := beatsToSendMinus1 === 0.U
      outgoingQueue.io.deq.ready := (state === sendData) && axi4.w.ready

      // Write Response handling
      axi4.b.ready := true.B

      val ackBeats = inflightBeatCounts.io.deq.bits.numBeats
      val ackFlush = inflightBeatCounts.io.deq.bits.isFlush
      when(axi4.b.fire) {
        readCredits := readCredits + (ackBeats * beatBytes.U) - bytesConsumedByCPU
        when(ackFlush) {
          val remainingBeatsToAck = Mux(ackBeats < flushBeatsToAck, flushBeatsToAck - ackBeats, 0.U)
          flushBeatsToAck := remainingBeatsToAck
          inFlush         := remainingBeatsToAck =/= 0.U
        }
      }
      inflightBeatCounts.io.deq.ready := axi4.b.fire
      assert(!axi4.b.valid || inflightBeatCounts.io.deq.valid)

      // We only use the write channels to implement FPGA-to-CPU streams
      axi4.ar.valid := false.B
      axi4.r.ready  := false.B

      // Register Driver-programmable MMIO registers
      ToCPUStreamDriverParameters(
        chParams.name,
        cpuBufferSizeBytes,
        attach(toHostPhysAddrHigh, s"${chParams.name}_toHostPhysAddrHigh"),
        attach(toHostPhysAddrLow, s"${chParams.name}_toHostPhysAddrLow"),
        attach(readCredits, s"${chParams.name}_bytesAvailable", ReadOnly),
        attach(bytesConsumedByCPU, s"${chParams.name}_bytesConsumed"),
        attach(doneInit, s"${chParams.name}_toHostStreamDoneInit"),
        attach(doFlush, s"${chParams.name}_toHostStreamFlush"),
        attach(!(doFlush || inFlush), s"${chParams.name}_toHostStreamFlushDone", ReadOnly),
      )
    }

    val sourceDriverParameters = if (hasStreams) {
      val axi4Bundles = toCPUNode.get.out.map(_._1)
      (for (((axi4IF, streamIF), params) <- axi4Bundles.zip(streamsToHostCPU).zip(sourceParams)) yield {
        chisel3.experimental.prefix(params.name) {
          elaborateToHostCPUStream(streamIF, axi4IF, params)
        }
      }).toSeq
    } else {
      Seq()
    }

    genCRFile()

    override def genHeader(base: BigInt, sb: StringBuilder) {
      val headerWidgetName = getWName.toUpperCase
      super.genHeader(base, sb)

      def serializeStreamParameters(prefix: String, params: Seq[ToCPUStreamDriverParameters]): Unit = {
        val numStreams = params.size
        sb.append(genConstStatic(s"${headerWidgetName}_${prefix}_stream_count", UInt32(numStreams)))

        // Hack: avoid emitting a zero-sized array by providing a dummy set of
        // parameters when no streams are generated. This is a limitation of the
        // current C emission strategy. Note, the actual number of streams is still reported above.
        val placeholder    = ToCPUStreamDriverParameters("UNUSED", 0, 0, 0, 0, 0, 0, 0, 0)
        val nonEmptyParams = if (numStreams == 0) Seq(placeholder) else params

        val arraysToEmit = Seq(
          "names"                      -> nonEmptyParams.map { p => CStrLit(p.name) },
          "fpgaBufferDepth"            -> nonEmptyParams.map { p => UInt32(p.fpgaBufferDepth) },
          "toHostPhysAddrHighAddrs"    -> nonEmptyParams.map { p => UInt64(base + p.toHostPhysAddrHighAddr) },
          "toHostPhysAddrLowAddrs"     -> nonEmptyParams.map { p => UInt64(base + p.toHostPhysAddrLowAddr) },
          "bytesAvailableAddrs"        -> nonEmptyParams.map { p => UInt64(base + p.bytesAvailableAddr) },
          "bytesConsumedAddrs"         -> nonEmptyParams.map { p => UInt64(base + p.bytesConsumedAddr) },
          "toHostStreamDoneInitAddrs"  -> nonEmptyParams.map { p => UInt64(base + p.toHostStreamDoneInitAddr) },
          "toHostStreamFlushAddrs"     -> nonEmptyParams.map { p => UInt64(base + p.toHostStreamFlushAddr) },
          "toHostStreamFlushDoneAddrs" -> nonEmptyParams.map { p => UInt64(base + p.toHostStreamFlushDoneAddr) },
        )

        for ((name, values) <- arraysToEmit) {
          sb.append(genArray(s"${headerWidgetName}_${prefix}_${name}", values))
        }
      }

      serializeStreamParameters("to_cpu", sourceDriverParameters)
    }
  }
}
