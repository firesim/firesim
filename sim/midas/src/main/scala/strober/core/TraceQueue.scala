// See LICENSE for license details.

package strober
package core

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.{Parameters, Field}

case object TraceMaxLen extends Field[Int]

class TraceQueueIO[T <: Data](data: T, val entries: Int) extends Bundle {
  val enq = Flipped(EnqIO(data.cloneType))
  val deq = Flipped(DeqIO(data.cloneType))
  val limit = Input(UInt(log2Ceil(entries).W))
}

class TraceQueue[T <: Data](data: T)(implicit p: Parameters) extends Module {
  //import Chisel._ // FIXME: due to a bug in SyncReadMem
  val io = IO(new TraceQueueIO(data, p(TraceMaxLen)))

  val do_flow = Wire(Bool())
  val do_enq = io.enq.fire() && !do_flow
  val do_deq = io.deq.fire() && !do_flow

  val maybe_full = RegInit(false.B)
  val enq_ptr = RegInit(0.U(log2Ceil(io.entries).W))
  val deq_ptr = RegInit(0.U(log2Ceil(io.entries).W))
  val enq_wrap = enq_ptr === (io.limit - 2.U)
  val deq_wrap = deq_ptr === (io.limit - 2.U)
  when (do_enq) { enq_ptr := Mux(enq_wrap, 0.U, enq_ptr + 1.U) }
  when (do_deq) { deq_ptr := Mux(deq_wrap, 0.U, deq_ptr + 1.U) }
  when (do_enq =/= do_deq) { maybe_full := do_enq }

  val ptr_match = enq_ptr === deq_ptr
  val empty = ptr_match && !maybe_full
  val full = ptr_match && maybe_full
  val atLeastTwo = full || enq_ptr - deq_ptr >= 2.U
  do_flow := empty && io.deq.ready

  val ram = SyncReadMem(io.entries, chiselTypeOf(data))
  when (do_enq) { ram.write(enq_ptr, io.enq.bits) }

  val ren = io.deq.ready && (atLeastTwo || !io.deq.valid && !empty)
  val ram_out_valid = RegNext(ren)
  val raddr = Mux(io.deq.valid, Mux(deq_wrap, 0.U, deq_ptr + 1.U), deq_ptr)

  io.enq.ready := !full
  io.deq.valid := Mux(empty, io.enq.valid, ram_out_valid)
  io.deq.bits  := Mux(empty, io.enq.bits, ram.read(raddr, ren))
}

object TraceQueue {
  def apply[T <: Data](
      token: DecoupledIO[T],
      len: UInt,
      name: String = "trace",
      full: Option[Bool] = None)
     (implicit p: Parameters) = {
    val queue = Module(new TraceQueue(token.bits))
    queue suggestName name
    // queue is written when a token is consumed
    queue.io.enq.bits  := token.bits
    queue.io.enq.valid := token.fire()
    queue.io.limit := len
    full match {
      case None =>
      case Some(p) => p := !queue.io.enq.ready
    }

    val trace = Queue(queue.io.deq, 1, pipe=true)

    // for debugging
    val count = RegInit(0.U(32.W))
    count suggestName s"${name}_count"
    when (trace.fire() =/= queue.io.enq.fire()) {
      count := Mux(trace.fire(), count - 1.U, count + 1.U)
    }

    trace
  }
}
