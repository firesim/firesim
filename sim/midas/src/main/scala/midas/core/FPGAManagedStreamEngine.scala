// See LICENSE for license details.

package midas.core

import chisel3._
import chisel3.util._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy._

import midas.widgets._
import midas.widgets.CppGenerationUtils._

class WriteMetadata(val numBeatsWidth: Int) extends Bundle {
  val numBeats = Output(UInt(numBeatsWidth.W))
  val isFlush  = Output(Bool())
}

class FPGAManagedStreamEngine(p: Parameters, val params: StreamEngineParameters) extends StreamEngine(p) {
  require(sinkParams.isEmpty, "FPGAManagedStreamEngine does not currently support FPGA-sunk streams.")

  // Picked arbitrarily
  val pagesPerBuffer = 4
  val maxFlight = pagesPerBuffer
  val bytesPerPage = 4096
  val bytesPerBeat = BridgeStreamConstants.streamWidthBits / 8
  require(isPow2(bytesPerBeat))
  val beatsPerPage = bytesPerPage / bytesPerBeat

  val cpuManagedAXI4NodeOpt = None

  val (fpgaManagedAXI4NodeOpt, toCPUNode) = if (hasStreams) {
    implicit val pShadow = p
    val xbar = AXI4Xbar()
    val toCPUNode = AXI4MasterNode(
      Seq(AXI4MasterPortParameters(
        sourceParams.map { p => AXI4MasterParameters(name = p.name, maxFlight = Some(maxFlight)) }
     )))
    xbar :=* AXI4Buffer() :=* toCPUNode
    (Some(xbar), Some(toCPUNode))
  } else {
    (None, None)
  }

  lazy val module = new WidgetImp(this) {
    val io = IO(new WidgetIO)

    case class ToCPUStreamDriverParameters(
      name: String,
      fpgaBufferDepth: Int,
      toHostPhysAddrHighAddr: Int,
      toHostPhysAddrLowAddr: Int,
      bytesAvailableAddr: Int,
      bytesConsumedAddr: Int,
      toHostStreamDoneInitAddr: Int,
      toHostStreamFlushAddr: Int,
      toHostStreamFlushDoneAddr: Int,
    )

  // Invoke this in the module implementation
    def elaborateToHostCPUStream(
        channel: DecoupledIO[UInt],
        axi4: AXI4Bundle,
        chParams: StreamSourceParameters,
        ): ToCPUStreamDriverParameters = {

      require(BridgeStreamConstants.streamWidthBits == axi4.params.dataBits, 
        s"FPGAManagedStreamEngine requires stream widths to match to-cpu AXI4 data width")
      val toHostCPUQueueDepth = chParams.fpgaBufferDepth
      require(toHostCPUQueueDepth > beatsPerPage)
      val bufferSizeBytes = (1 << log2Ceil(toHostCPUQueueDepth)) * (BridgeStreamConstants.streamWidthBits/8)
      // This to simplify the hardware
      require(isPow2(bufferSizeBytes))

      val toHostPhysAddrHigh   = Reg(UInt(32.W))
      val toHostPhysAddrLow    = Reg(UInt(32.W))
      val bytesConsumedByCPU   = RegInit(0.U(log2Ceil(bufferSizeBytes + 1).W))

      val outgoingQueue = Module(new BRAMQueue(2 * beatsPerPage)(UInt(BridgeStreamConstants.streamWidthBits.W)))
      outgoingQueue.io.enq <> channel

      val writeCredits = RegInit(bufferSizeBytes.U(log2Ceil(bufferSizeBytes + 1).W))
      val readCredits  = RegInit(0.U(log2Ceil(bufferSizeBytes + 1).W))
      val writePtr     = RegInit(0.U(log2Ceil(bufferSizeBytes).W))
      val doneInit     = RegInit(false.B)
      val doFlush      = RegInit(false.B)
      val flushBeatsToIssue, flushBeatsToAck = RegInit(0.U(log2Ceil(toHostCPUQueueDepth+1).W))
      val inflightBeatCounts = Module(new Queue(new WriteMetadata(log2Ceil(beatsPerPage + 1)), maxFlight))

      val idle :: sendData :: Nil = Enum(2)
      val state = RegInit(idle)
      val beatsToSendMinus1 = RegInit(0.U(log2Ceil(beatsPerPage).W))

      // Ensure we do not cross page boundaries. Again -1, for AXI4 encoding of length
      val beatsToPageBoundary =
        beatsPerPage.U - writePtr(log2Ceil(bytesPerPage) - 1, log2Ceil(bytesPerBeat))
      assert((beatsToPageBoundary > 0.U) && (beatsToPageBoundary <= (beatsPerPage.U)))

      // BeatsToPageBoundary covers the end of the circular queue only because
      // we ensure the buffer size is a multiple of page size
      val bounds = Seq(
        outgoingQueue.io.count,        // Available beats
        writeCredits >> log2Ceil(bytesPerBeat).U, // Space on host CPU
        beatsToPageBoundary)           // Length to end of page

      val writeableBeats = bounds.reduce { (a, b) => Mux(a < b, a, b) }
      val writeableBeatsMinus1 = writeableBeats - 1.U

      // This register resets itself to 0 on cycles it is not set by the host
      // CPU.  If it is non-zero it was written to in the last cycle, and so we
      // know we can update credits.
      // NB: This assumes there cannot be back-to-back MMIO accesses.
      when (bytesConsumedByCPU =/= 0.U) {
        printf("Driver Credit: %d\n", bytesConsumedByCPU)
        bytesConsumedByCPU := 0.U
        writeCredits := writeCredits + bytesConsumedByCPU
        readCredits  := readCredits - bytesConsumedByCPU
      }

      // When the driver calls for a flush, write at least as many beats to the
      // CPU as are currently avalable in the outgoingQueue. More may be written
      // if new data is enqueued.
      //slel
      // Waiting for the FSM to go idle ensures io.count will not be decremented
      // in elthe same cycle. Maybe not necessary?
      when (doFlush && (state === idle)) {
        printf("Driver calls for flush of %d beats\n", outgoingQueue.io.count)
        doFlush := false.B
        flushBeatsToIssue := outgoingQueue.io.count
        flushBeatsToAck   := outgoingQueue.io.count
      }

      assert(readCredits >= bytesConsumedByCPU,
        "Driver read more bytes than available in circular buffer.")
      assert((writeCredits + bytesConsumedByCPU) <= bufferSizeBytes.U,
        "Driver granted more write credit than physically allowable.")

      switch (state) {
        is (idle) {
          when(axi4.aw.fire) {
            state := sendData
            beatsToSendMinus1 := writeableBeatsMinus1
            writePtr := writePtr + (writeableBeats * bytesPerBeat.U)
            writeCredits := writeCredits + bytesConsumedByCPU - (writeableBeats * bytesPerBeat.U)
            flushBeatsToIssue := Mux(flushBeatsToIssue < writeableBeats, 0.U, flushBeatsToIssue - writeableBeats)
          }
        }
        is (sendData) {
          when(axi4.w.fire) {
            state := Mux(axi4.w.bits.last, idle, sendData)
            printf("Beats to send: %d\n", beatsToSendMinus1)
            beatsToSendMinus1 := beatsToSendMinus1 - 1.U
          }
        }
      }

      printf("State: %d, Read Credits: %d, Write Credits: %d, Count: %d, flushBeats: %d\n", state,readCredits, writeCredits, outgoingQueue.io.count, flushBeatsToIssue)

      when(axi4.aw.fire) {
        printf(p"PCIM AW Fire ${axi4.aw}\n")
      }
      when(axi4.w.fire) {
        printf(p"PCIM W Fire ${axi4.w}\n")
      }
      when(axi4.b.fire) {
        printf(p"PCIM B Fire ${axi4.b}\n")
      }


      axi4.aw.valid :=
        (state === idle) &&
        (inflightBeatCounts.io.enq.ready) &&
        ((flushBeatsToIssue =/= 0.U) ||
        (writeableBeats === beatsToPageBoundary))


      axi4.aw.bits.id    := 0.U
      // NOTE: Transactions must not span page boundaries.
      axi4.aw.bits.addr := Cat(toHostPhysAddrHigh, toHostPhysAddrLow) + writePtr
      axi4.aw.bits.len  := writeableBeatsMinus1
      axi4.aw.bits.size := (log2Ceil(bytesPerBeat)).U
      // This is assumed but not exposed by the PCIM interface
      axi4.aw.bits.burst := AXI4Parameters.BURST_INCR
      // This to permit intermediate width adapters, etc, to pack narrower
      // transcations into larger ones, in the event we make this IF narrower than 512b
      axi4.aw.bits.cache := AXI4Parameters.CACHE_MODIFIABLE
      // Assume page-sized transfers for now
      // The following are unused by AWS's PCIM IF
      axi4.aw.bits.prot  := DontCare
      axi4.aw.bits.qos   := DontCare
      axi4.aw.bits.lock  := DontCare

      inflightBeatCounts.io.enq.valid := axi4.aw.fire
      inflightBeatCounts.io.enq.bits.numBeats := writeableBeats
      inflightBeatCounts.io.enq.bits.isFlush  := flushBeatsToIssue =/= 0.U

      //assert((state =/= sendData) || outgoingQueue.io.deq.valid, "Outgoing queue unexpectedly empty.")
      axi4.w.valid := (state === sendData) && outgoingQueue.io.deq.valid
      axi4.w.bits.data  := outgoingQueue.io.deq.bits
      axi4.w.bits.strb  := ((BigInt(1) << bytesPerBeat) - 1).U
      axi4.w.bits.last  := beatsToSendMinus1 === 0.U
      outgoingQueue.io.deq.ready := (state === sendData) && axi4.w.ready

      // Write Response handling
      axi4.b.ready := true.B
      val ackBeats = inflightBeatCounts.io.deq.bits.numBeats
      val ackFlush = inflightBeatCounts.io.deq.bits.isFlush
      when (axi4.b.fire) {
        readCredits := readCredits + (ackBeats * bytesPerBeat.U) - bytesConsumedByCPU
        when (ackFlush) {
          flushBeatsToAck := Mux(ackBeats < flushBeatsToAck, flushBeatsToAck - ackBeats, 0.U)
          printf("Acking flush for %d, remaining: %d \n", ackBeats, flushBeatsToAck)
        }
      }
      inflightBeatCounts.io.deq.ready := axi4.b.fire
      assert(!axi4.b.valid || inflightBeatCounts.io.deq.valid)

      // Tie-off read channel.
      axi4.ar.valid := false.B
      axi4.r .ready := false.B

      // Register Driver-programmable MMIO registers
      ToCPUStreamDriverParameters(
        chParams.name,
        bufferSizeBytes,
        attach(toHostPhysAddrHigh, "toHostPhysAddrHigh"),
        attach(toHostPhysAddrLow, "toHostPhysAddrLow"),
        attach(readCredits, "bytesAvailable", ReadOnly),
        attach(bytesConsumedByCPU, "bytesConsumed"),
        attach(doneInit, "toHostStreamDoneInit"),
        attach(doFlush, "toHostStreamFlush"),
        attach(!doFlush && (flushBeatsToAck === 0.U), "toHostStreamFlushDone", ReadOnly),
      )
    }
    val axi4Bundles = toCPUNode.get.out.map(_._1)

    val sourceDriverParameters = (for ((axi4IF, streamIF, params) <- (axi4Bundles, streamsToHostCPU, sourceParams).zipped) yield {
      elaborateToHostCPUStream(streamIF, axi4IF, params)
    }).toSeq

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
        val placeholder = ToCPUStreamDriverParameters("UNUSED", 0, 0, 0, 0, 0, 0, 0, 0)
        val nonEmptyParams = if (numStreams == 0) Seq(placeholder) else params

        val arraysToEmit = Seq(
          "names"         -> nonEmptyParams.map { p => CStrLit(p.name) },
          "fpgaBufferDepth" -> nonEmptyParams.map { p => UInt32(p.fpgaBufferDepth) },
          "toHostPhysAddrHighAddrs" -> nonEmptyParams.map { p => UInt64(base + p.toHostPhysAddrHighAddr) },
          "toHostPhysAddrLowAddrs" -> nonEmptyParams.map { p => UInt64(base + p.toHostPhysAddrLowAddr) },
          "bytesAvailableAddrs" -> nonEmptyParams.map { p => UInt64(base + p.bytesAvailableAddr) },
          "bytesConsumedAddrs" -> nonEmptyParams.map { p => UInt64(base + p.bytesConsumedAddr) },
          "toHostStreamDoneInitAddrs" -> nonEmptyParams.map { p => UInt64(base + p.toHostStreamDoneInitAddr) },
          "toHostStreamFlushAddrs" -> nonEmptyParams.map { p => UInt64(base + p.toHostStreamFlushAddr) },
          "toHostStreamFlushDoneAddrs" -> nonEmptyParams.map { p => UInt64(base + p.toHostStreamFlushDoneAddr) },
        )

        for ((name, values) <- arraysToEmit) {
          sb.append(genArray(s"${headerWidgetName}_${prefix}_${name}", values))
        }
      }

      serializeStreamParameters("to_cpu",   sourceDriverParameters)
    }
  }
}
