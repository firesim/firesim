// See LICENSE for license details.

package midas.core

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.axi4._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._

import midas.widgets._
import midas.targetutils.FireSimQueueHelper

import firesim.lib.bridgeutils._

/** Platform stream engine for hosts that provide the design an AXI4 memory master into host-observable memory (AWS
  * PCIM, and the SLASH-shell V80's host_mem port joined into the target-DRAM xbar).
  *
  * To-host streams: the engine DMAs stream beats from per-stream ring buffers located in that shared memory; the driver
  * programs each ring's base address through MMIO and tracks occupancy with credits.
  *
  * From-host streams: the engine fetches beats from driver-filled ring buffers through the same AXI4 master. The driver
  * writes beats over its platform bulk path and rings a per-stream doorbell register; the design consumes on credit.
  *
  * Ring buffers are sized to a power-of-two multiple of the 512-bit stream width and respect 4 KiB AXI4 burst
  * boundaries. The engine adapts between the 512-bit stream domain and whatever data width the AXI4 edge negotiates
  * (e.g. 64-bit on the V80 host_mem port) with StreamWidthAdapter.
  */
class DDRManagedStreamEngine(p: Parameters, val params: StreamEngineParameters) extends StreamEngine(p) {

  // Bytes per stream beat (512-bit stream domain)
  val beatBytes = BridgeStreamConstants.streamWidthBits / 8

  // Host-observable DDR rings use page-aligned bases and 4 KiB AXI4 boundaries.
  val pageBytes = 4096
  val pageBeats = pageBytes / beatBytes

  def maxFlightForStream(params: StreamSourceParameters): Int =
    (params.fpgaBufferDepth * beatBytes) / pageBytes

  def maxFlightForSink(params: StreamSinkParameters): Int =
    (params.fpgaBufferDepth * beatBytes) / pageBytes

  val cpuManagedAXI4NodeOpt = None

  val (fpgaManagedAXI4NodeOpt, toCPUNodeOpt, fromCPUNodeOpt) = if (hasStreams) {
    // The implicit val defined in StreamEngine is not accessible here; Make a
    // duplicate that can be referenced by diplomatic nodes
    implicit val pShadow = p
    val xbar             = AXI4Xbar()
    val toCPUNodeOpt     =
      if (sourceParams.nonEmpty)
        Some(
          AXI4MasterNode(
            sourceParams.map { p =>
              AXI4MasterPortParameters(
                Seq(AXI4MasterParameters(name = p.name, maxFlight = Some(maxFlightForStream(p))))
              )
            }
          )
        )
      else None
    val fromCPUNodeOpt   =
      if (sinkParams.nonEmpty)
        Some(
          AXI4MasterNode(
            sinkParams.map { p =>
              AXI4MasterPortParameters(Seq(AXI4MasterParameters(name = p.name, maxFlight = Some(maxFlightForSink(p)))))
            }
          )
        )
      else None
    toCPUNodeOpt.foreach(n => xbar :=* AXI4Buffer() :=* n)
    fromCPUNodeOpt.foreach(n => xbar :=* AXI4Buffer() :=* n)
    (Some(xbar), toCPUNodeOpt, fromCPUNodeOpt)
  } else {
    (None, None, None)
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

    case class FromCPUStreamDriverParameters(
      name:                        String,
      fpgaBufferDepth:             Int,
      fromHostPhysAddrHighAddr:    Int,
      fromHostPhysAddrLowAddr:     Int,
      bytesAvailableAddr:          Int,
      bytesConsumedAddr:           Int,
      fromHostStreamDoneInitAddr:  Int,
      fromHostStreamFlushAddr:     Int,
      fromHostStreamFlushDoneAddr: Int,
    )

    // Invoke this in the module implementation
    def elaborateToHostCPUStream(
      channel:          DecoupledIO[UInt],
      axi4:             AXI4Bundle,
      chParams:         StreamSourceParameters,
      maxTransferBytes: Int,
    ): ToCPUStreamDriverParameters = {
      require(
        isPow2(axi4.params.dataBits / 8) && (axi4.params.dataBits / 8) <= beatBytes,
        "DDRManagedStreamEngine requires the AXI4 data width to be a power-of-two number of bytes no wider than the stream width",
      )
      val axiBeatBytes        = axi4.params.dataBits / 8
      val axiBeatsPerBeat     = beatBytes / axiBeatBytes
      val pageAxiBeats        = pageBytes / axiBeatBytes
      val maxBurstBeats       = math.min(pageBytes, math.min(maxTransferBytes, 256 * axiBeatBytes)) / beatBytes
      require(maxBurstBeats > 0, "Memory port must support at least one stream beat per burst")
      val cpuBufferDepthBeats = chParams.fpgaBufferDepth
      require(cpuBufferDepthBeats > pageBeats)
      val cpuBufferSizeBytes  = (1 << log2Ceil(cpuBufferDepthBeats)) * beatBytes
      // This to simplify the hardware
      require(isPow2(cpuBufferSizeBytes))

      val toHostPhysAddrHigh = Reg(UInt(32.W))
      val toHostPhysAddrLow  = Reg(UInt(32.W))
      val bytesConsumedByCPU = RegInit(0.U(log2Ceil(cpuBufferSizeBytes + 1).W))

      // This sets up a double buffer that should give full throughput for a
      // single stream system. This queue could be grown under a multi-stream system.
      val outgoingQueueIO =
        FireSimQueueHelper.makeIO(UInt(BridgeStreamConstants.streamWidthBits.W), 2 * pageBeats, isFireSim = true)

      outgoingQueueIO.enq <> channel

      // Adapt between the 512-bit stream domain and the negotiated AXI4 edge.
      val ser_des = Module(new StreamWidthAdapter(axi4.params.dataBits, BridgeStreamConstants.streamWidthBits))
      // unused
      ser_des.io.narrow.in.bits  := 0.U
      ser_des.io.narrow.in.valid := false.B
      ser_des.io.wide.out.ready  := false.B

      // Count queued beats until a burst has been selected. Unconditional
      // prefetch into the width adapter hides the final beat from count.
      val beatsToLoad = RegInit(0.U(log2Ceil(pageBeats + 1).W))
      ser_des.io.wide.in.bits   := outgoingQueueIO.deq.bits
      ser_des.io.wide.in.valid  := outgoingQueueIO.deq.valid && beatsToLoad =/= 0.U
      outgoingQueueIO.deq.ready := ser_des.io.wide.in.ready && beatsToLoad =/= 0.U
      when(ser_des.io.wide.in.fire) { beatsToLoad := beatsToLoad - 1.U }

      val writeCredits = RegInit(cpuBufferSizeBytes.U(log2Ceil(cpuBufferSizeBytes + 1).W))
      val readCredits        = RegInit(0.U(log2Ceil(cpuBufferSizeBytes + 1).W))
      val writePtr           = RegInit(0.U(log2Ceil(cpuBufferSizeBytes).W))
      val doneInit           = RegInit(false.B)
      // Key assumption: write acknowledgements can be used as a synchronization
      // point, after which the CPU can read new data written into its circular
      // buffer. This tracks inflight requests, to increment read credits on
      // write acknowledgement, and to cap maxflight.
      val inflightBeatCounts = Module(
        new Queue(new WriteMetadata(log2Ceil(pageAxiBeats + 1)), maxFlightForStream(chParams))
      )

      val idle :: sendAddress :: sendData :: Nil = Enum(3)
      val state                                  = RegInit(idle)
      val beatsToSendMinus1                      = RegInit(0.U(log2Ceil(pageAxiBeats + 1).W))

      // Ensure we do not cross page boundaries per AXI4 spec.
      val beatsToPageBoundary = pageBeats.U - writePtr(log2Ceil(pageBytes) - 1, log2Ceil(beatBytes))
      assert((beatsToPageBoundary > 0.U) && (beatsToPageBoundary <= (pageBeats.U)))

      // Establish the largest write request we can make, by doing a min
      // reduction over the following bounds (in stream beats):
      val writeBounds = Seq(
        maxBurstBeats.U,                       // AXI4 and negotiated downstream transfer limit
        outgoingQueueIO.count,                 // Beats available for enqueue in local FPGA buffer
        writeCredits >> log2Ceil(beatBytes).U, // Space available in cpu buffer
        beatsToPageBoundary,
      ) // Length to end of page
      // NB: BeatsToPageBoundary covers the end of the circular buffer only because
      // we ensure the buffer size is a multiple of page size

      val writeableBeats    = writeBounds.reduce { (a, b) => Mux(a < b, a, b) }
      val issuedBeats       = Reg(UInt(log2Ceil(pageBeats + 1).W))
      val writeableAxiBeats = issuedBeats * axiBeatsPerBeat.U

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
          when(doFlush && !inFlush && (outgoingQueueIO.count > 0.U)) {
            inFlush           := true.B
            flushBeatsToIssue := outgoingQueueIO.count
            flushBeatsToAck   := outgoingQueueIO.count
          }
          // A bridge can stop producing while waiting for its last tokens
          // to reach the host. Issue short bursts too, including the tail of
          // a NIC batch whose length is not a multiple of a full burst.
          val start = doneInit && inflightBeatCounts.io.enq.ready && (writeableBeats =/= 0.U)

          when(start) {
            issuedBeats := writeableBeats
            state       := sendAddress
          }
        }
        is(sendAddress) {
          when(axi4.aw.fire) {
            state             := sendData
            beatsToSendMinus1 := writeableAxiBeats - 1.U
            beatsToLoad       := issuedBeats
            writePtr          := writePtr + (issuedBeats * beatBytes.U)
            writeCredits      := writeCredits + bytesConsumedByCPU - (issuedBeats * beatBytes.U)
            flushBeatsToIssue := Mux(flushBeatsToIssue < issuedBeats, 0.U, flushBeatsToIssue - issuedBeats)
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
      axi4.aw.bits.len   := writeableAxiBeats - 1.U
      axi4.aw.bits.size  := (log2Ceil(axiBeatBytes)).U
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
      inflightBeatCounts.io.enq.bits.numBeats := writeableAxiBeats
      inflightBeatCounts.io.enq.bits.isFlush  := flushBeatsToIssue =/= 0.U

      axi4.w.valid                := (state === sendData) && ser_des.io.narrow.out.valid
      axi4.w.bits.data            := ser_des.io.narrow.out.bits
      axi4.w.bits.strb            := ((BigInt(1) << axiBeatBytes) - 1).U
      axi4.w.bits.last            := beatsToSendMinus1 === 0.U
      ser_des.io.narrow.out.ready := (state === sendData) && axi4.w.ready

      // Write Response handling
      axi4.b.ready := true.B

      val ackBeats = inflightBeatCounts.io.deq.bits.numBeats
      val ackFlush = inflightBeatCounts.io.deq.bits.isFlush
      when(axi4.b.fire) {
        assert(axi4.b.bits.resp === 0.U, "DDR stream write response error")
        readCredits := readCredits + (ackBeats * axiBeatBytes.U) - bytesConsumedByCPU
        when(ackFlush) {
          // Convert acknowledged AXI beats back to stream beats
          val ackStreamBeats      = ackBeats >> log2Ceil(axiBeatsPerBeat).U
          val remainingBeatsToAck = Mux(ackStreamBeats < flushBeatsToAck, flushBeatsToAck - ackStreamBeats, 0.U)
          flushBeatsToAck := remainingBeatsToAck
          inFlush         := remainingBeatsToAck =/= 0.U
        }
      }
      inflightBeatCounts.io.deq.ready := axi4.b.fire
      assert(!axi4.b.valid || inflightBeatCounts.io.deq.valid)

      // We only use the write channels to implement FPGA-to-CPU streams
      axi4.ar.valid := false.B
      axi4.ar.bits  := DontCare
      axi4.r.ready  := false.B

      // Register Driver-programmable MMIO registers
      ToCPUStreamDriverParameters(
        chParams.name,
        cpuBufferSizeBytes,
        attach(toHostPhysAddrHigh, s"${chParams.name}_toHostPhysAddrHigh", substruct = false),
        attach(toHostPhysAddrLow, s"${chParams.name}_toHostPhysAddrLow", substruct   = false),
        attach(readCredits, s"${chParams.name}_bytesAvailable", ReadOnly, substruct  = false),
        attach(bytesConsumedByCPU, s"${chParams.name}_bytesConsumed", substruct      = false),
        attach(doneInit, s"${chParams.name}_toHostStreamDoneInit", substruct         = false),
        attach(doFlush, s"${chParams.name}_toHostStreamFlush", substruct             = false),
        attach(
          !(doFlush || inFlush) && state === idle && outgoingQueueIO.count === 0.U && !inflightBeatCounts.io.deq.valid,
          s"${chParams.name}_toHostStreamFlushDone",
          ReadOnly,
          substruct                                                                  = false,
        ),
      )
    }

    // Driver writes beats into a ring buffer in shared memory and rings a
    // per-stream doorbell; the design fetches beats on credit through the
    // platform's AXI4 memory master.
    def elaborateFromHostCPUStream(
      channel:          DecoupledIO[UInt],
      axi4:             AXI4Bundle,
      chParams:         StreamSinkParameters,
      maxTransferBytes: Int,
    ): FromCPUStreamDriverParameters = {
      require(
        isPow2(axi4.params.dataBits / 8) && (axi4.params.dataBits / 8) <= beatBytes,
        "DDRManagedStreamEngine requires the AXI4 data width to be a power-of-two number of bytes no wider than the stream width",
      )
      val axiBeatBytes        = axi4.params.dataBits / 8
      val axiBeatsPerBeat     = beatBytes / axiBeatBytes
      val pageAxiBeats        = pageBytes / axiBeatBytes
      val maxBurstBeats       = math.min(pageBytes, math.min(maxTransferBytes, 256 * axiBeatBytes)) / beatBytes
      require(maxBurstBeats > 0, "Memory port must support at least one stream beat per burst")
      val cpuBufferDepthBeats = chParams.fpgaBufferDepth
      require(cpuBufferDepthBeats > pageBeats)
      val cpuBufferSizeBytes  = (1 << log2Ceil(cpuBufferDepthBeats)) * beatBytes
      require(isPow2(cpuBufferSizeBytes))

      val fromHostPhysAddrHigh   = Reg(UInt(32.W))
      val fromHostPhysAddrLow    = Reg(UInt(32.W))
      val doneInit               = RegInit(false.B)
      // Doorbell: driver writes the number of bytes newly pushed since its
      // previous doorbell; the design adds them to its read credits and
      // clears the register on the next cycle.
      val bytesAvailableFromHost = RegInit(0.U(32.W))
      // Cumulative bytes copied out of the DDR ring (wraps mod 2^32).
      // The driver computes free space: free = size - (pushed - consumed) mod 2^32.
      val bytesConsumedTotal     = RegInit(0.U(32.W))

      val readCredits = RegInit(0.U(log2Ceil(cpuBufferSizeBytes + 1).W))
      val readPtr     = RegInit(0.U(log2Ceil(cpuBufferSizeBytes).W))

      val ser_des = Module(new StreamWidthAdapter(axi4.params.dataBits, BridgeStreamConstants.streamWidthBits))
      // unused
      ser_des.io.narrow.out.ready := false.B
      ser_des.io.wide.in.bits     := 0.U
      ser_des.io.wide.in.valid    := false.B

      // DDR responses share the return channel with target-memory traffic.
      // Reserve storage for a whole burst before AR: a target stalled on
      // memory may backpressure its NIC input, but must never prevent the
      // already-issued DDR burst from draining and releasing that channel.
      val incomingDepth   = p(DDRManagedStreamReadBufferDepth)
      require(incomingDepth > 0 && isPow2(incomingDepth))
      val incomingQueueIO =
        FireSimQueueHelper.makeIO(UInt(BridgeStreamConstants.streamWidthBits.W), incomingDepth, isFireSim = true)
      incomingQueueIO.enq <> ser_des.io.wide.out
      channel <> incomingQueueIO.deq
      // Includes data still in flight, in the width adapter, and in the FIFO.
      // Only delivery to the bridge releases a reserved slot.
      val reservedBeats = RegInit(0.U(log2Ceil(incomingDepth + 1).W))
      val freeBeats     = incomingDepth.U - reservedBeats
      assert(reservedBeats <= incomingDepth.U, "FPGA-managed read burst exceeded reserved response storage")

      val idle :: sendAddress :: recvData :: Nil = Enum(3)
      val state                                  = RegInit(idle)
      val beatsToRecvMinus1                      = RegInit(0.U(log2Ceil(pageAxiBeats + 1).W))

      val doFlush = RegInit(false.B)

      when(bytesAvailableFromHost =/= 0.U) {
        readCredits            := readCredits + bytesAvailableFromHost
        bytesAvailableFromHost := 0.U
      }
      assert(
        (readCredits + bytesAvailableFromHost) <= cpuBufferSizeBytes.U,
        "Driver granted more read credit than physically allowable.",
      )

      // Largest read burst we can issue (in stream beats): bounded by
      // credits, AXI4 page boundaries, and the end of the ring.
      val pageBeatsLeft    = pageBeats.U - readPtr(log2Ceil(pageBytes) - 1, log2Ceil(beatBytes))
      val ringBeatsLeft    = (cpuBufferSizeBytes.U >> log2Ceil(beatBytes).U) - (readPtr >> log2Ceil(beatBytes).U)
      val creditBeats      = readCredits >> log2Ceil(beatBytes).U
      val pullableBeats    = Seq(pageBeatsLeft, ringBeatsLeft, creditBeats, maxBurstBeats.U, freeBeats).reduce { (a, b) =>
        Mux(a < b, a, b)
      }
      val issuedBeats      = Reg(UInt(log2Ceil(pageBeats + 1).W))
      val pullableAxiBeats = issuedBeats * axiBeatsPerBeat.U
      reservedBeats := reservedBeats + Mux(axi4.ar.fire, issuedBeats, 0.U) - channel.fire.asUInt

      switch(state) {
        is(idle) {
          when(doneInit && (pullableBeats =/= 0.U)) {
            issuedBeats := pullableBeats
            state       := sendAddress
          }
        }
        is(sendAddress) {
          when(axi4.ar.fire) {
            state             := recvData
            beatsToRecvMinus1 := pullableAxiBeats - 1.U
            readCredits       := readCredits + bytesAvailableFromHost - (issuedBeats * beatBytes.U)
            readPtr           := readPtr + (issuedBeats * beatBytes.U)
          }
        }
        is(recvData) {
          when(axi4.r.fire && axi4.r.bits.last) { state := idle }
        }
      }

      axi4.ar.valid      := (state === sendAddress)
      axi4.ar.bits.id    := 0.U
      axi4.ar.bits.addr  := (Cat(fromHostPhysAddrHigh, fromHostPhysAddrLow) + readPtr)(axi4.params.addrBits - 1, 0)
      axi4.ar.bits.len   := pullableAxiBeats - 1.U
      axi4.ar.bits.size  := (log2Ceil(axiBeatBytes)).U
      axi4.ar.bits.burst := AXI4Parameters.BURST_INCR
      axi4.ar.bits.cache := AXI4Parameters.CACHE_MODIFIABLE
      axi4.ar.bits.prot  := 0.U
      axi4.ar.bits.qos   := 0.U
      axi4.ar.bits.lock  := 0.U

      when(axi4.ar.fire) { beatsToRecvMinus1 := pullableAxiBeats - 1.U }
      when(axi4.r.fire) {
        assert(axi4.r.bits.resp === 0.U, "DDR stream read response error")
        assert(axi4.r.bits.last === (beatsToRecvMinus1 === 0.U), "DDR stream read last mismatch")
        beatsToRecvMinus1 := beatsToRecvMinus1 - 1.U
      }
      axi4.r.ready               := (state === recvData) && ser_des.io.narrow.in.ready
      ser_des.io.narrow.in.valid := (state === recvData) && axi4.r.valid
      ser_des.io.narrow.in.bits  := axi4.r.bits.data

      // Write-side channels of this per-stream master stay idle.
      axi4.aw.valid := false.B
      axi4.aw.bits  := DontCare
      axi4.w.valid  := false.B
      axi4.w.bits   := DontCare
      axi4.b.ready  := false.B

      // A completed copy into the local response FIFO releases the DDR ring
      // slot; the independent reservation remains until bridge delivery.
      when(ser_des.io.wide.out.fire) {
        bytesConsumedTotal := bytesConsumedTotal + beatBytes.U
      }

      // Flush: driver writes the flush register and polls flushDone. The
      // design holds flushDone low until every doorbelled byte has been
      // issued and the final read burst has been handed to the bridge
      // channel (readCredits only decremented at AR issue; returning to
      // reservedBeats also covers data still buffered before bridge delivery).
      when(
        doFlush && (readCredits === 0.U) && (bytesAvailableFromHost === 0.U) &&
          (state === idle) && (reservedBeats === 0.U)
      ) { doFlush := false.B }

      FromCPUStreamDriverParameters(
        chParams.name,
        cpuBufferSizeBytes,
        attach(fromHostPhysAddrHigh, s"${chParams.name}_fromHostPhysAddrHigh", substruct  = false),
        attach(fromHostPhysAddrLow, s"${chParams.name}_fromHostPhysAddrLow", substruct    = false),
        attach(bytesAvailableFromHost, s"${chParams.name}_bytesAvailable", substruct      = false),
        attach(bytesConsumedTotal, s"${chParams.name}_bytesConsumed", ReadOnly, substruct = false),
        attach(doneInit, s"${chParams.name}_fromHostStreamDoneInit", substruct            = false),
        attach(doFlush, s"${chParams.name}_fromHostStreamFlush", substruct                = false),
        attach(!doFlush, s"${chParams.name}_fromHostStreamFlushDone", ReadOnly, substruct = false),
      )
    }

    val sourceDriverParameters = if (sourceParams.nonEmpty) {
      (for ((((axi4IF, edge), streamIF), params) <- toCPUNodeOpt.get.out.zip(streamsToHostCPU).zip(sourceParams))
        yield {
          chisel3.experimental.prefix(params.name) {
            elaborateToHostCPUStream(streamIF, axi4IF, params, edge.slave.maxTransfer)
          }
        }).toSeq
    } else {
      Seq()
    }

    val sinkDriverParameters = if (hasStreams && sinkParams.nonEmpty) {
      (for ((((axi4IF, edge), streamIF), params) <- fromCPUNodeOpt.get.out.zip(streamsFromHostCPU).zip(sinkParams))
        yield {
          chisel3.experimental.prefix(params.name) {
            elaborateFromHostCPUStream(streamIF, axi4IF, params, edge.slave.maxTransfer)
          }
        }).toSeq
    } else {
      Seq()
    }

    genCRFile()

    override def genHeader(base: BigInt, memoryRegions: Map[String, BigInt], sb: StringBuilder): Unit = {
      def serializeStreamParameters(params: Seq[ToCPUStreamDriverParameters]): StdVector = {
        StdVector(
          "FPGAManagedStreams::StreamParameters",
          params.map(p => Verbatim(s"""|FPGAManagedStreams::StreamParameters(
                                       |  std::string(${CStrLit(p.name).toC}),
                                       |  ${UInt32(p.fpgaBufferDepth).toC},
                                       |  ${UInt64(base + p.toHostPhysAddrHighAddr).toC},
                                       |  ${UInt64(base + p.toHostPhysAddrLowAddr).toC},
                                       |  ${UInt64(base + p.bytesAvailableAddr).toC},
                                       |  ${UInt64(base + p.bytesConsumedAddr).toC},
                                       |  ${UInt64(base + p.toHostStreamDoneInitAddr).toC},
                                       |  ${UInt64(base + p.toHostStreamFlushAddr).toC},
                                       |  ${UInt64(base + p.toHostStreamFlushDoneAddr).toC}
                                       |)""".stripMargin)),
        )
      }

      def serializeSinkStreamParameters(params: Seq[FromCPUStreamDriverParameters]): StdVector = {
        StdVector(
          "FPGAManagedStreams::FromCPUStreamParameters",
          params.map(p => Verbatim(s"""|FPGAManagedStreams::FromCPUStreamParameters(
                                       |  std::string(${CStrLit(p.name).toC}),
                                       |  ${UInt32(p.fpgaBufferDepth).toC},
                                       |  ${UInt64(base + p.fromHostPhysAddrHighAddr).toC},
                                       |  ${UInt64(base + p.fromHostPhysAddrLowAddr).toC},
                                       |  ${UInt64(base + p.bytesAvailableAddr).toC},
                                       |  ${UInt64(base + p.bytesConsumedAddr).toC},
                                       |  ${UInt64(base + p.fromHostStreamDoneInitAddr).toC},
                                       |  ${UInt64(base + p.fromHostStreamFlushAddr).toC},
                                       |  ${UInt64(base + p.fromHostStreamFlushDoneAddr).toC}
                                       |)""".stripMargin)),
        )
      }

      genConstructor(
        base,
        sb,
        "FPGAManagedStreamWidget",
        "fpga_managed_stream",
        Seq(
          Verbatim("FPGAManagedStreams::Target::HostMemory"),
          serializeStreamParameters(sourceDriverParameters),
          serializeSinkStreamParameters(sinkDriverParameters),
        ),
        "GET_MANAGED_STREAM_CONSTRUCTOR",
      )

    }
  }
}
