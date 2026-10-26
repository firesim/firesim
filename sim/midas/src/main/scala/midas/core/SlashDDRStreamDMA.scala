// See LICENSE for license details.

package midas.core

import chisel3._
import chisel3.util._
import freechips.rocketchip.amba.axi4._

/** DDR transport around the upstream CPUManagedStreamEngine. Its queues, credits, stream address decoding and
  * 64/512-bit adapters remain upstream. Only full tokens are moved DDR transactions never cross a 4KiB boundary.
  */
class SlashDDRStreamDMA(
  memParams:         AXI4BundleParameters,
  streamParams:      AXI4BundleParameters,
  reservedBytes:     BigInt,
  availableBytes:    BigInt,
  maximumBurstBytes: Int,
) extends Module {
  require(memParams.dataBits == 64 && streamParams.dataBits == 64)
  require(maximumBurstBytes >= 8 && maximumBurstBytes % 8 == 0)
  val maxBeats                                                                                          = math.min(256, maximumBurstBytes / 8)
  val io                                                                                                = IO(new Bundle {
    val mem           = new AXI4Bundle(memParams)
    val stream        = new AXI4Bundle(streamParams)
    val address       = Input(UInt(64.W))
    val streamAddress = Input(UInt(32.W))
    val bytes         = Input(UInt(32.W))
    val command       = Flipped(Decoupled(UInt(32.W))) // 1: DDR->stream, 2: stream->DDR
    val status        = Output(UInt(32.W))
  })
  val idle :: readAddress :: readData :: readDrain :: writeAddress :: writeData :: writeResponse :: Nil = Enum(7)
  val state                                                                                             = RegInit(idle)
  val done                                                                                              = RegInit(false.B)
  val error                                                                                             = RegInit(false.B)
  val address                                                                                           = Reg(UInt(64.W))
  val streamAddress                                                                                     = Reg(UInt(32.W))
  val remaining                                                                                         = Reg(UInt(32.W))
  val burstBeats                                                                                        = Reg(UInt(9.W))
  val left                                                                                              = Reg(UInt(9.W))
  val fetched                                                                                           = Reg(UInt(9.W))
  val readQueue                                                                                         = Module(new Queue(UInt(64.W), 16))
  val writeQueue                                                                                        = Module(new Queue(UInt(64.W), 16))
  io.command.ready := state === idle
  io.status        := Cat(0.U(29.W), error, done, state =/= idle)
  when(io.command.fire) {
    done  := false.B
    error := false.B
    val legal = (io.command.bits === 1.U || io.command.bits === 2.U) &&
      io.bytes =/= 0.U && io.bytes <= (2 * 1024 * 1024).U && io.bytes(5, 0) === 0.U &&
      io.streamAddress(5, 0) === 0.U &&
      io.address(5, 0) === 0.U && io.address >= reservedBytes.U &&
      io.address < availableBytes.U && (io.address +& io.bytes) <= availableBytes.U
    when(legal) {
      assert(readQueue.io.count === 0.U && writeQueue.io.count === 0.U)
      address       := io.address
      streamAddress := io.streamAddress
      remaining     := io.bytes
      state         := Mux(io.command.bits === 1.U, readAddress, writeAddress)
    }.otherwise {
      error := true.B
      done  := true.B
    }
  }
  val pageBeats = (4096.U(13.W) - address(11, 0)) >> 3
  val count     = Mux((remaining >> 3) < maxBeats.U, remaining >> 3, maxBeats.U)
  val nextBeats = Mux(pageBeats < count, pageBeats, count)
  io.mem.aw.valid         := state === writeAddress
  io.mem.aw.bits          := 0.U.asTypeOf(io.mem.aw.bits)
  io.mem.aw.bits.addr     := address
  io.mem.aw.bits.len      := nextBeats - 1.U
  io.mem.aw.bits.size     := 3.U
  io.mem.aw.bits.burst    := 1.U
  io.mem.ar.valid         := state === readAddress
  io.mem.ar.bits          := 0.U.asTypeOf(io.mem.ar.bits)
  io.mem.ar.bits.addr     := address
  io.mem.ar.bits.len      := nextBeats - 1.U
  io.mem.ar.bits.size     := 3.U
  io.mem.ar.bits.burst    := 1.U
  when(io.mem.ar.fire) {
    burstBeats := nextBeats
    left       := nextBeats
    state      := readData
  }
  when(io.mem.aw.fire) {
    burstBeats := nextBeats
    left       := nextBeats
    fetched    := 0.U
    state      := writeData
  }
  readQueue.io.enq.valid  := state === readData && io.mem.r.valid && io.mem.r.bits.resp === 0.U
  readQueue.io.enq.bits   := io.mem.r.bits.data
  io.mem.r.ready          := state === readData && (readQueue.io.enq.ready || io.mem.r.bits.resp =/= 0.U)
  when(io.mem.r.fire) {
    when(io.mem.r.bits.resp =/= 0.U || io.mem.r.bits.last =/= (left === 1.U)) { error := true.B }
    left      := left - 1.U
    address   := address + 8.U
    remaining := remaining - 8.U
    when(left === 1.U) { state := readDrain }
  }
  // Upstream stream AXI uses an atomic AW/W/B handshake for each beat.
  val sending = state === readData || state === readDrain
  io.stream.aw.bits       := 0.U.asTypeOf(io.stream.aw.bits)
  io.stream.aw.bits.addr  := streamAddress
  io.stream.aw.bits.size  := 3.U
  io.stream.aw.valid      := sending && readQueue.io.deq.valid
  io.stream.w.bits        := 0.U.asTypeOf(io.stream.w.bits)
  io.stream.w.bits.data   := readQueue.io.deq.bits
  io.stream.w.bits.strb   := 255.U
  io.stream.w.bits.last   := true.B
  io.stream.w.valid       := sending && readQueue.io.deq.valid
  io.stream.b.ready       := true.B
  readQueue.io.deq.ready  := sending && io.stream.aw.ready && io.stream.w.ready && io.stream.b.valid
  when(io.stream.b.fire) { assert(io.stream.b.bits.resp === 0.U && io.stream.aw.fire && io.stream.w.fire) }
  when(state === readDrain && readQueue.io.count === 0.U) {
    when(remaining === 0.U || error) {
      state := idle
      done  := true.B
    }.otherwise { state := readAddress }
  }
  val fetching = state === writeData && fetched < burstBeats
  io.stream.ar.bits       := 0.U.asTypeOf(io.stream.ar.bits)
  io.stream.ar.bits.addr  := streamAddress
  io.stream.ar.bits.size  := 3.U
  io.stream.ar.valid      := fetching && writeQueue.io.enq.ready
  io.stream.r.ready       := fetching && writeQueue.io.enq.ready
  writeQueue.io.enq.valid := fetching && io.stream.r.valid
  writeQueue.io.enq.bits  := io.stream.r.bits.data
  when(writeQueue.io.enq.fire) {
    fetched := fetched + 1.U
    assert(io.stream.ar.fire && io.stream.r.bits.last && io.stream.r.bits.resp === 0.U)
  }
  io.mem.w.valid          := state === writeData && writeQueue.io.deq.valid
  io.mem.w.bits           := 0.U.asTypeOf(io.mem.w.bits)
  io.mem.w.bits.data      := writeQueue.io.deq.bits
  io.mem.w.bits.strb      := 255.U
  io.mem.w.bits.last      := left === 1.U
  writeQueue.io.deq.ready := state === writeData && io.mem.w.ready
  when(io.mem.w.fire) {
    left      := left - 1.U
    address   := address + 8.U
    remaining := remaining - 8.U
    when(left === 1.U) { state := writeResponse }
  }
  io.mem.b.ready          := state === writeResponse
  when(io.mem.b.fire) {
    when(io.mem.b.bits.resp =/= 0.U) { error := true.B }
    when(remaining === 0.U || error || io.mem.b.bits.resp =/= 0.U) {
      state := idle
      done  := true.B
    }.otherwise {
      state := writeAddress
    }
  }
}
