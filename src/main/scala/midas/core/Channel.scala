package midas
package core

import config.Parameters
import util.ParameterizedBundle

import chisel3._
import chisel3.util._

class WireChannelIO(w: Int)(implicit p: Parameters) extends Bundle {
  val in    = Flipped(Decoupled(UInt(w.W)))
  val out   = Decoupled(UInt(w.W))
  val trace = Decoupled(UInt(w.W))
  val traceLen = Input(UInt(log2Up(p(TraceMaxLen)+1).W))
  override def cloneType = new WireChannelIO(w)(p).asInstanceOf[this.type]
}

class WireChannel(val w: Int)(implicit p: Parameters) extends Module {
  val io = IO(new WireChannelIO(w))
  val tokens = Module(new Queue(UInt(w.W), p(ChannelLen)))
  tokens.io.enq <> io.in
  io.out <> tokens.io.deq
  if (p(EnableSnapshot)) {
    io.trace <> TraceQueue(tokens.io.deq, io.traceLen)
  } else {
    io.trace.valid := Bool(false)
  }
}

class SimReadyValidIO[T <: Data](gen: T) extends Bundle {
  val target = EnqIO(gen)
  val host = new widgets.HostReadyValid
  override def cloneType = new SimReadyValidIO(gen).asInstanceOf[this.type]
}

object SimReadyValid {
  def apply[T <: Data](gen: T) = new SimReadyValidIO(gen)
}

class ReadyValidChannelIO[T <: Data](gen: T)(implicit p: Parameters) extends Bundle {
  val enq = Flipped(SimReadyValid(gen))
  val deq = SimReadyValid(gen)
  val targetReset = Input(Bool())
  override def cloneType = new ReadyValidChannelIO(gen)(p).asInstanceOf[this.type]
}

class ReadyValidChannel[T <: Data](gen: T)(implicit p: Parameters) extends Module {
  val io = IO(new ReadyValidChannelIO(gen))
  val target = Module(new Queue(gen, 2)) // needs more?
  val tokens = Module(new Queue(Bool(), p(ChannelLen))) // keep enq handshakes

  target.reset := io.targetReset

  target.io.enq.bits := io.enq.target.bits
  target.io.enq.valid := io.enq.target.valid && io.enq.host.hValid
  io.enq.target.ready := target.io.enq.ready
  io.enq.host.hReady := tokens.io.enq.ready
  tokens.io.enq.bits := target.io.enq.fire()
  tokens.io.enq.valid := io.enq.host.hValid

  val deqCnts = RegInit(0.U(8.W))
  val deqValid = tokens.io.deq.bits || deqCnts.orR
  io.deq.target.bits := target.io.deq.bits
  io.deq.target.valid := target.io.deq.valid && deqValid
  target.io.deq.ready := io.deq.target.ready && io.deq.host.hReady && deqValid
  io.deq.host.hValid := tokens.io.deq.valid
  tokens.io.deq.ready := io.deq.host.hReady

  when(tokens.io.deq.fire()) {
    // target value is valid, but not ready
    when(tokens.io.deq.bits && !io.deq.target.ready) {
      deqCnts := deqCnts + 1.U
    }.elsewhen(!tokens.io.deq.bits && io.deq.target.ready && deqCnts.orR) {
      deqCnts := deqCnts - 1.U
    }
  }
}
