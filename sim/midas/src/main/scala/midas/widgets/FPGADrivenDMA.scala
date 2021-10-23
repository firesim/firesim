// See LICENSE for license details.

package midas.widgets

import chisel3._
import chisel3.util._

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._

object BridgeStreamConstants {
  val pcimWidthBits = 512
  // The size of the circular buffer in host memory.
  val pagesPerBuffer  = 8
  val bytesPerPage = 4096
  val bytesPerBeat = pcimWidthBits / 8
  val bufferSizeBytes = pagesPerBuffer * bytesPerPage
  val beatsPerPage = bytesPerPage / bytesPerBeat
}

class WriteMetadata(val numBeatsWidth: Int) extends Bundle {
  val numBeats = Output(UInt(numBeatsWidth.W))
  val isFlush  = Output(Bool())
}

trait HasToHostStream { self: Widget =>
  import BridgeStreamConstants._

  // Picked arbitrarily
  val maxFlight = pagesPerBuffer

  val toHostStreamNode = AXI4MasterNode(
    Seq(AXI4MasterPortParameters(
      Seq(AXI4MasterParameters(name = "Test", maxFlight = Some(maxFlight)))
    ))
  )

  def toHostCPUQueueDepth: Int

  // Invoke this in the module implementation
  def elaborateStreamController(context: WidgetImp): DecoupledIO[UInt] = {
    require(toHostCPUQueueDepth > beatsPerPage)
    val axi4 = toHostStreamNode.out.head._1

    val toHostPhysAddrHigh   = Reg(UInt(32.W))
    val toHostPhysAddrLow    = Reg(UInt(32.W))
    val bytesConsumedByCPU   = RegInit(0.U(log2Ceil(bufferSizeBytes + 1).W))

    val outgoingQueue = Module(new BRAMQueue(toHostCPUQueueDepth)(UInt(pcimWidthBits.W)))
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

    val bounds = Seq(
      outgoingQueue.io.count,        // Available beats
      writeCredits / bytesPerBeat.U, // Space on host CPU
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
    // CPU as are currently available in the outgoingQueue. More may be written
    // if new data is enqueued.
    //
    // Waiting for the FSM to go idle ensures io.count will not be decremented
    // in the same cycle. Maybe not necessary?
    when (doFlush && (state === idle)) {
      printf("Driver calls for flush of %d beats\n", outgoingQueue.io.count)
      doFlush := false.B
      flushBeatsToIssue := outgoingQueue.io.count
      flushBeatsToAck   := outgoingQueue.io.count
    }.elsewhen(axi4.w.fire) {
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
    axi4.aw.bits.size := (log2Ceil(pcimWidthBits / 8)).U
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
    axi4.w.bits.strb  := ((BigInt(1) << (pcimWidthBits / 8)) - 1).U
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
    context.attach(toHostPhysAddrHigh, "toHostPhysAddrHigh")
    context.attach(toHostPhysAddrLow, "toHostPhysAddrLow")
    context.attach(readCredits, "bytesAvailable", ReadOnly)
    context.attach(bytesConsumedByCPU, "bytesConsumed")
    context.attach(doneInit, "toHostStreamDoneInit")
    context.attach(doFlush, "toHostStreamFlush")
    context.attach(!doFlush && (flushBeatsToAck === 0.U), "toHostStreamFlushDone", ReadOnly)

    outgoingQueue.io.enq
  }
}
