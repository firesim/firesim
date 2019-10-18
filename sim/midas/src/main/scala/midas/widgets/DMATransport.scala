// See LICENSE for license details.

package midas
package widgets

import core.{DMANastiKey}
import junctions._

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.util.{DecoupledHelper}

trait HasDMA {
  self: Widget =>
  val dma = IO(Flipped(new NastiIO()( p.alterPartial({ case NastiKey => p(DMANastiKey) }))))
  // Specify the size that you want the address region to be in the DMA memory map
  // For proper functioning, the region should be at least as big as the
  // largest read/write request you plan to send over PCIS
  val dmaBytes = dma.nastiXDataBits / 8
  val dmaSize: BigInt
}

trait DMAToHostCPU extends HasDMA {
  self: Widget =>
  val toHostCPUQueueDepth: Int

  require(dma.nastiXDataBits == 512, "DMA width must be 512 bits (PCIS)")

  //val incomingPCISdat = Module(new SplitSeqQueue)
  val outgoingPCISdat = Module(new BRAMQueue(toHostCPUQueueDepth)(UInt(dma.nastiXDataBits.W)))

  // incoming/outgoing queue counts to replace ready/valid for batching
  val outgoingCount = RegInit(0.U(32.W))

  when (outgoingPCISdat.io.enq.fire() && outgoingPCISdat.io.deq.fire()) {
    outgoingCount := outgoingCount
  } .elsewhen (outgoingPCISdat.io.enq.fire()) {
    outgoingCount := outgoingCount + 1.U
  } .elsewhen (outgoingPCISdat.io.deq.fire()) {
    outgoingCount := outgoingCount - 1.U
  } .otherwise {
    outgoingCount := outgoingCount
  }

  // check to see if pcis has valid output instead of waiting for timeouts
  attach(outgoingPCISdat.io.deq.valid, "pcis_out_valid", ReadOnly)
  attach(outgoingCount, "outgoing_count", ReadOnly)

  val ar_queue = Queue(dma.ar)

  assert(!ar_queue.valid || ar_queue.bits.size === log2Ceil(dmaBytes).U)

  val readHelper = DecoupledHelper(
    ar_queue.valid,
    dma.r.ready,
    outgoingPCISdat.io.deq.valid
  )

  val readBeatCounter = RegInit(0.U(9.W))
  val lastReadBeat = readBeatCounter === ar_queue.bits.len
  when (dma.r.fire()) {
    readBeatCounter := Mux(lastReadBeat, 0.U, readBeatCounter + 1.U)
  }

  outgoingPCISdat.io.deq.ready := readHelper.fire(outgoingPCISdat.io.deq.valid)

  dma.r.valid := readHelper.fire(dma.r.ready)
  dma.r.bits.data := outgoingPCISdat.io.deq.bits
  dma.r.bits.resp := 0.U(2.W)
  dma.r.bits.last := lastReadBeat
  dma.r.bits.id := ar_queue.bits.id
  dma.r.bits.user := ar_queue.bits.user
  ar_queue.ready := readHelper.fire(ar_queue.valid, lastReadBeat)
}

trait DMAFromHostCPU extends HasDMA {
  self: Widget =>
  val fromHostCPUQueueDepth: Int

  require(dma.nastiXDataBits == 512, "DMA width must be 512 bits (PCIS)")

  val incomingPCISdat = Module(new BRAMQueue(fromHostCPUQueueDepth)(UInt(dma.nastiXDataBits.W)))

  // incoming/outgoing queue counts to replace ready/valid for batching
  val incomingCount = RegInit(0.U(32.W))

  when (incomingPCISdat.io.enq.fire() && incomingPCISdat.io.deq.fire()) {
    incomingCount := incomingCount
  } .elsewhen (incomingPCISdat.io.enq.fire()) {
    incomingCount := incomingCount + 1.U
  } .elsewhen (incomingPCISdat.io.deq.fire()) {
    incomingCount := incomingCount - 1.U
  } .otherwise {
    incomingCount := incomingCount
  }

  // check to see if pcis is ready to accept data instead of forcing writes
  attach(incomingPCISdat.io.deq.valid, "pcis_in_busy", ReadOnly)
  attach(incomingCount, "incoming_count", ReadOnly)

  val aw_queue = Queue(dma.aw)
  val w_queue = Queue(dma.w)

  assert(!aw_queue.valid || aw_queue.bits.size === log2Ceil(dmaBytes).U)
  assert(!w_queue.valid  || w_queue.bits.strb === ~0.U(dmaBytes.W))

  val writeHelper = DecoupledHelper(
    aw_queue.valid,
    w_queue.valid,
    dma.b.ready,
    incomingPCISdat.io.enq.ready
  )

  val writeBeatCounter = RegInit(0.U(9.W))
  val lastWriteBeat = writeBeatCounter === aw_queue.bits.len
  when (w_queue.fire()) {
    writeBeatCounter := Mux(lastWriteBeat, 0.U, writeBeatCounter + 1.U)
  }

  dma.b.bits.resp := 0.U(2.W)
  dma.b.bits.id := aw_queue.bits.id
  dma.b.bits.user := aw_queue.bits.user
  dma.b.valid := writeHelper.fire(dma.b.ready, lastWriteBeat)
  aw_queue.ready := writeHelper.fire(aw_queue.valid, lastWriteBeat)
  w_queue.ready := writeHelper.fire(w_queue.valid)

  incomingPCISdat.io.enq.valid := writeHelper.fire(incomingPCISdat.io.enq.ready)
  incomingPCISdat.io.enq.bits := w_queue.bits.data
}


trait TieOffDMAToHostCPU extends HasDMA {
  self: Widget =>
  dma.ar.ready := false.B
  dma.r.valid := false.B
  dma.r.bits := DontCare
}

trait TieOffDMAFromHostCPU extends HasDMA {
  self: Widget =>
  dma.aw.ready := false.B
  dma.w.ready := false.B
  dma.b.valid := false.B
  dma.b.bits := DontCare
}

// Complete Bridge mixins for DMA-based transport
trait UnidirectionalDMAToHostCPU extends HasDMA with DMAToHostCPU with TieOffDMAFromHostCPU {
  self: Widget =>
}

trait UnidirecitonalDMAFromHostCPU extends HasDMA with DMAFromHostCPU with TieOffDMAToHostCPU {
  self: Widget =>
}

trait BidirectionalDMA extends HasDMA with DMAFromHostCPU with DMAToHostCPU {
  self: Widget =>
}
