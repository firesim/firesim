// See LICENSE for license details.

package midas.core

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.axi4._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._

import midas.widgets._
import midas.targetutils.FireSimQueueHelper
import midas.targetutils.xdc

import firesim.lib.bridgeutils._

/** Where an FPGAManagedStreamEngine writes the streams it sources.
  *
  * This is a property of an *engine instance*, not of the host platform: a single design may carry one engine of each
  * kind (e.g. F2 doing host DMA for bridge streams while FireAxe partitioning drives a peer FPGA), and they share only
  * the physical PCIM port.
  */
sealed trait FPGAManagedStreamTarget

/** Write into a circular buffer in host DRAM, drained by the driver. Requires credit accounting, since the CPU consumes
  * asynchronously, and prefers large (page-sized) bursts for PCIe efficiency.
  */
case object HostMemoryTarget extends FPGAManagedStreamTarget

/** Write into a peer FPGA's BAR (FireAxe partitioning). There is no consumer to grant credits, and latency matters more
  * than bandwidth, so transfers are issued per beat.
  */
case object PeerFPGATarget extends FPGAManagedStreamTarget

class WriteMetadata(val numBeatsWidth: Int) extends Bundle {
  val numBeats = Output(UInt(numBeatsWidth.W))
  val isFlush  = Output(Bool())
}

class FPGAManagedStreamEngine(
  p:          Parameters,
  val params: StreamEngineParameters,
  val target: FPGAManagedStreamTarget = HostMemoryTarget,
) extends StreamEngine(p) {
  require(target == HostMemoryTarget || sinkParams.isEmpty, "Peer FPGA streams cannot have host-to-FPGA sinks.")
  require(sinkParams.isEmpty || p(CPUManagedAXI4Key).nonEmpty, "H2C descriptors require a CPU-managed AXI4 port.")

  // Beats refers to 512b words moving over a stream
  val beatBytes = BridgeStreamConstants.streamWidthBits / 8

  // For P2P, send an AXI4 transaction per beat rather than accumulating 4kB:
  // waiting for a full page wastes PCIe bandwidth when partitioning, where
  // latency dominates. Host DMA wants the opposite -- page-sized bursts, so a
  // drain is one large transfer rather than thousands of small ones.
  val streamEngineForP2P = target == PeerFPGATarget
  val pageBytes          = if (streamEngineForP2P) beatBytes else 4096
  val pageBeats          = pageBytes / beatBytes

  def maxFlightForStream(params: StreamSourceParameters): Int =
    (params.fpgaBufferDepth * beatBytes) / pageBytes

  // BAR4 carries only H2C descriptors. The payload is fetched over PCIM.
  val h2cPCISNodeOpt: Option[AXI4SlaveNode] = if (sinkParams.nonEmpty) {
    val pcisParams = p(CPUManagedAXI4Key).get
    Some(
      AXI4SlaveNode(
        Seq(
          AXI4SlavePortParameters(
            slaves = Seq(
              AXI4SlaveParameters(
                address       = Seq(AddressSet(0, (BigInt(1) << pcisParams.addrBits) - 1)),
                resources     = (new MemoryDevice).reg,
                regionType    = RegionType.UNCACHED,
                executable    = false,
                // Diplomacy requires maxTransfer >= the 64-byte PCIS bus
                // width, even though the descriptor register protocol uses
                // narrow 4-byte writes and rejects larger accesses.
                supportsWrite = TransferSizes(4, pcisParams.dataBits / 8),
                supportsRead  = TransferSizes.none,
                interleavedId = Some(0),
              )
            ),
            beatBytes = pcisParams.dataBits / 8,
          )
        )
      )
    )
  } else None
  val cpuManagedAXI4NodeOpt = h2cPCISNodeOpt

  val (fpgaManagedAXI4NodeOpt, toCPUNode) = if (hasStreams) {
    // The implicit val defined in StreamEngine is not accessible here; Make a
    // duplicate that can be referenced by diplomatic nodes
    implicit val pShadow = p
    val xbar             = AXI4Xbar()
    val toCPUNode        = AXI4MasterNode(
      sourceParams.map { p =>
        AXI4MasterPortParameters(Seq(AXI4MasterParameters(name = p.name, maxFlight = Some(maxFlightForStream(p)))))
      } ++ sinkParams.map { p =>
        AXI4MasterPortParameters(Seq(AXI4MasterParameters(name = p.name, maxFlight = Some(1))))
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
        "FPGAManagedStreamEngine requires stream widths to match FPGA-managed AXI4 data width",
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
      val outgoingQueueIO =
        FireSimQueueHelper.makeIO(UInt(BridgeStreamConstants.streamWidthBits.W), 2 * pageBeats, isFireSim = true)

      outgoingQueueIO.enq <> channel

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
      val beatsToSendMinus1                      = RegInit(0.U(log2Ceil(pageBeats + 1).W))

      // Ensure we do not cross page boundaries per AXI4 spec.
      val beatsToPageBoundary = if (streamEngineForP2P) {
        pageBeats.U
      } else {
        pageBeats.U - writePtr(log2Ceil(pageBytes) - 1, log2Ceil(beatBytes))
      }
      assert((beatsToPageBoundary > 0.U) && (beatsToPageBoundary <= (pageBeats.U)))

      // Establish the largest AXI4 write request we can make, by doing a min
      // reduction over the following bounds:
      val writeBounds = Seq(
        outgoingQueueIO.count,                 // Beats available for enqueue in local FPGA buffer
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
      if (!streamEngineForP2P) {
        when(bytesConsumedByCPU =/= 0.U) {
          bytesConsumedByCPU := 0.U
          writeCredits       := writeCredits + bytesConsumedByCPU
          readCredits        := readCredits - bytesConsumedByCPU
        }
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
            if (!streamEngineForP2P) {
              writeCredits := writeCredits + bytesConsumedByCPU - (writeableBeats * beatBytes.U)
            }
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

      axi4.w.valid              := (state === sendData) && outgoingQueueIO.deq.valid
      axi4.w.bits.data          := outgoingQueueIO.deq.bits
      axi4.w.bits.strb          := ((BigInt(1) << beatBytes) - 1).U
      axi4.w.bits.last          := beatsToSendMinus1 === 0.U
      outgoingQueueIO.deq.ready := (state === sendData) && axi4.w.ready

      // Write Response handling
      axi4.b.ready := true.B

      val ackBeats = inflightBeatCounts.io.deq.bits.numBeats
      val ackFlush = inflightBeatCounts.io.deq.bits.isFlush
      when(axi4.b.fire) {
        if (!streamEngineForP2P) {
          readCredits := readCredits + (ackBeats * beatBytes.U) - bytesConsumedByCPU
        }
        when(ackFlush) {
          val remainingBeatsToAck = Mux(ackBeats < flushBeatsToAck, flushBeatsToAck - ackBeats, 0.U)
          flushBeatsToAck := remainingBeatsToAck
          inFlush         := remainingBeatsToAck =/= 0.U
        }
      }
      inflightBeatCounts.io.deq.ready := axi4.b.fire
      assert(!axi4.b.valid || inflightBeatCounts.io.deq.valid)

      // The H2C mover uses a separate AXI master on the same PCIM crossbar.
      axi4.ar.valid := false.B
      axi4.r.ready  := false.B

      // Register Driver-programmable MMIO registers
      ToCPUStreamDriverParameters(
        chParams.name,
        cpuBufferSizeBytes,
        attach(toHostPhysAddrHigh, s"${chParams.name}_toHostPhysAddrHigh", substruct                 = false),
        attach(toHostPhysAddrLow, s"${chParams.name}_toHostPhysAddrLow", substruct                   = false),
        attach(readCredits, s"${chParams.name}_bytesAvailable", ReadOnly, substruct                  = false),
        attach(bytesConsumedByCPU, s"${chParams.name}_bytesConsumed", substruct                      = false),
        attach(doneInit, s"${chParams.name}_toHostStreamDoneInit", substruct                         = false),
        attach(doFlush, s"${chParams.name}_toHostStreamFlush", substruct                             = false),
        attach(!(doFlush || inFlush), s"${chParams.name}_toHostStreamFlushDone", ReadOnly, substruct = false),
      )
    }

    val sourceDriverParameters = if (hasStreams) {
      val axi4Bundles = toCPUNode.get.out.take(sourceParams.size).map(_._1)
      (for (((axi4IF, streamIF), params) <- axi4Bundles.zip(streamsToHostCPU).zip(sourceParams)) yield {
        chisel3.experimental.prefix(params.name) {
          elaborateToHostCPUStream(streamIF, axi4IF, params)
        }
      }).toSeq
    } else {
      Seq()
    }

    case class FromCPUStreamDriverParameters(
      name:               String,
      streamId:           Int,
      fpgaBufferDepth:    Int,
      fifoCountAddr:      Int,
      descriptorDoneAddr: Int,
      descriptorErrorAddr: Int,
    )

    // PCIS carries one ordered descriptor at a time. The doorbell selects a
    // stream; each selected mover then runs independently on the PCIM xbar.
    val h2cStarts = Wire(Vec(sinkParams.size, Bool()))
    val h2cReady = Wire(Vec(sinkParams.size, Bool()))
    h2cStarts.foreach(_ := false.B)
    val hostAddrLow  = RegInit(0.U(32.W))
    val hostAddrHigh = RegInit(0.U(32.W))
    val lengthBytes  = RegInit(0.U(32.W))
    if (sinkParams.nonEmpty) {
      val pcis = h2cPCISNodeOpt.get.in.head._1

      val writeIdle :: writeData :: writeResponse :: Nil = Enum(3)
      val writeState = RegInit(writeIdle)
      val writeAddr  = Reg(UInt(pcis.aw.bits.addr.getWidth.W))
      val writeID    = Reg(UInt(pcis.aw.bits.id.getWidth.W))
      val writeBad   = RegInit(false.B)
      pcis.aw.ready := writeState === writeIdle
      when(pcis.aw.fire) {
        writeAddr  := pcis.aw.bits.addr
        writeID    := pcis.aw.bits.id
        writeBad   := pcis.aw.bits.len =/= 0.U || pcis.aw.bits.size =/= 2.U ||
          pcis.aw.bits.addr(1, 0) =/= 0.U || pcis.aw.bits.addr >= 16.U
        writeState := writeData
      }
      pcis.w.ready := writeState === writeData
      val lane     = writeAddr(5, 2)
      val word     = (pcis.w.bits.data >> (lane << 5))(31, 0)
      val strobe   = (pcis.w.bits.strb >> (lane << 2))(3, 0)
      when(pcis.w.fire) {
        when(!pcis.w.bits.last || strobe =/= 15.U) { writeBad := true.B }
        when(!writeBad && pcis.w.bits.last && strobe === 15.U) {
          switch(writeAddr(3, 2)) {
            is(0.U) { hostAddrLow := word }
            is(1.U) { hostAddrHigh := word }
            is(2.U) { lengthBytes := word }
            is(3.U) {
              val validLength = lengthBytes =/= 0.U && lengthBytes(5, 0) === 0.U && hostAddrLow(5, 0) === 0.U
              val selectedReady = sinkParams.indices.map(i => (word === i.U) && h2cReady(i)).reduce(_ || _)
              when(validLength && selectedReady) {
                sinkParams.indices.foreach { i =>
                  when(word === i.U) { h2cStarts(i) := true.B }
                }
              }.otherwise { writeBad := true.B }
            }
          }
        }
        when(pcis.w.bits.last) { writeState := writeResponse }
      }
      pcis.b.valid     := writeState === writeResponse
      pcis.b.bits.id   := writeID
      pcis.b.bits.resp := Mux(writeBad, 2.U, 0.U)
      pcis.b.bits.user := DontCare
      when(pcis.b.fire) { writeState := writeIdle }

      // Invalid reads complete with an error rather than timing out PCIS.
      val readValid = RegInit(false.B)
      val readID    = Reg(UInt(pcis.ar.bits.id.getWidth.W))
      val readLeft  = Reg(UInt(pcis.ar.bits.len.getWidth.W))
      pcis.ar.ready := !readValid
      when(pcis.ar.fire) {
        readID    := pcis.ar.bits.id
        readLeft  := pcis.ar.bits.len
        readValid := true.B
      }
      pcis.r.valid     := readValid
      pcis.r.bits.id   := readID
      pcis.r.bits.data := 0.U
      pcis.r.bits.resp := 2.U
      pcis.r.bits.last := readLeft === 0.U
      pcis.r.bits.user := DontCare
      when(pcis.r.fire) {
        when(readLeft === 0.U) { readValid := false.B }
        .otherwise { readLeft := readLeft - 1.U }
      }
    }

    val sinkDriverParameters = sinkParams.zipWithIndex.map { case (chParams, streamId) =>
      val axi4 = toCPUNode.get.out(sourceParams.size + streamId)._1
      val depth = chParams.fpgaBufferDepth
      require(depth > 0)
      require(axi4.params.dataBits == BridgeStreamConstants.streamWidthBits)

      val incomingQueueIO = FireSimQueueHelper.makeIO(
        UInt(BridgeStreamConstants.streamWidthBits.W),
        depth,
        isFireSim = true,
        overrideStyle = Some(xdc.RAMStyles.ULTRA),
      )
      streamsFromHostCPU(streamId) <> incomingQueueIO.deq

      // Four ordered 32-bit BAR4 stores form a descriptor. The last store at
      // offset 0xC is the doorbell; its value selects this stream.
      val descriptor = Module(new Queue(new Bundle {
        val address = UInt(64.W)
        val length = UInt(32.W)
      }, 2))
      descriptor.io.enq.valid := h2cStarts(streamId)
      descriptor.io.enq.bits.address := Cat(hostAddrHigh, hostAddrLow)
      descriptor.io.enq.bits.length := lengthBytes
      h2cReady(streamId) := descriptor.io.enq.ready
      val busy         = RegInit(false.B)
      val completed    = RegInit(0.U(32.W))
      val error        = RegInit(false.B)
      val idle :: sendAddress :: receiveData :: Nil = Enum(3)
      val state          = RegInit(idle)
      val nextAddr       = Reg(UInt(64.W))
      val remainingBeats = Reg(UInt(32.W))
      val burstBeats     = Reg(UInt(7.W))
      val beatsToReceive = Reg(UInt(7.W))
      descriptor.io.deq.ready := state === idle && !busy
      when(descriptor.io.deq.fire) {
        nextAddr       := descriptor.io.deq.bits.address
        remainingBeats := descriptor.io.deq.bits.length >> 6
        busy           := true.B
      }

      val freeBeats = depth.U - incomingQueueIO.count
      val toPageEnd = 64.U - nextAddr(11, 6)
      val bounds    = Seq(remainingBeats, freeBeats, toPageEnd, 64.U)
      val canRead   = bounds.reduce((a, b) => Mux(a < b, a, b))
      when(state === idle && busy && canRead =/= 0.U) {
        burstBeats := canRead
        state      := sendAddress
      }
      axi4.ar.valid      := state === sendAddress
      axi4.ar.bits.id    := 0.U
      axi4.ar.bits.addr  := nextAddr
      axi4.ar.bits.len   := burstBeats - 1.U
      axi4.ar.bits.size  := 6.U // 512 bits
      axi4.ar.bits.burst := AXI4Parameters.BURST_INCR
      axi4.ar.bits.cache := AXI4Parameters.CACHE_MODIFIABLE
      axi4.ar.bits.prot  := 0.U
      axi4.ar.bits.qos   := 0.U
      axi4.ar.bits.lock  := 0.U
      when(axi4.ar.fire) {
        beatsToReceive := burstBeats
        state          := receiveData
      }

      axi4.r.ready                := state === receiveData && incomingQueueIO.enq.ready
      incomingQueueIO.enq.valid := state === receiveData && axi4.r.valid
      incomingQueueIO.enq.bits  := axi4.r.bits.data
      when(axi4.r.fire) {
        when(axi4.r.bits.resp =/= 0.U || axi4.r.bits.last =/= (beatsToReceive === 1.U)) {
          error := true.B
        }
        beatsToReceive := beatsToReceive - 1.U
        when(axi4.r.bits.last) {
          nextAddr       := nextAddr + (burstBeats << 6)
          remainingBeats := remainingBeats - burstBeats
          state          := idle
          when(remainingBeats === burstBeats) {
            busy      := false.B
            completed := completed + 1.U
          }
        }
      }
      axi4.aw.valid := false.B
      axi4.aw.bits  := DontCare
      axi4.w.valid  := false.B
      axi4.w.bits   := DontCare
      axi4.b.ready  := true.B

      FromCPUStreamDriverParameters(
        chParams.name,
        streamId,
        depth,
        attach(incomingQueueIO.count, s"${chParams.name}_h2cCount", ReadOnly, substruct = false),
        attach(completed, s"${chParams.name}_h2cCompleted", ReadOnly, substruct = false),
        attach(error, s"${chParams.name}_h2cError", ReadOnly, substruct = false),
      )
    }.toSeq

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

      def serializeH2CParameters(params: Seq[FromCPUStreamDriverParameters]): StdVector = {
        StdVector(
          "FPGAManagedStreams::H2CStreamParameters",
          params.map(p => Verbatim(s"""|FPGAManagedStreams::H2CStreamParameters(
                                       |  std::string(${CStrLit(p.name).toC}),
                                       |  ${UInt32(p.streamId).toC},
                                       |  ${UInt32(p.fpgaBufferDepth).toC},
                                       |  ${UInt64(base + p.fifoCountAddr).toC},
                                       |  ${UInt64(base + p.descriptorDoneAddr).toC},
                                       |  ${UInt64(base + p.descriptorErrorAddr).toC}
                                       |)""".stripMargin)),
        )
      }

      val targetLiteral = target match {
        case HostMemoryTarget => "FPGAManagedStreams::Target::HostMemory"
        case PeerFPGATarget   => "FPGAManagedStreams::Target::PeerFPGA"
      }

      genConstructor(
        base,
        sb,
        "FPGAManagedStreamWidget",
        "fpga_managed_stream",
        Seq(Verbatim(targetLiteral), serializeStreamParameters(sourceDriverParameters), serializeH2CParameters(sinkDriverParameters)),
        "GET_MANAGED_STREAM_CONSTRUCTOR",
      )

    }
  }
}
