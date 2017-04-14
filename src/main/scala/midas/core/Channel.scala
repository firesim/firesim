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
  val host = new HostReadyValid
  override def cloneType = new SimReadyValidIO(gen).asInstanceOf[this.type]
}

object SimReadyValid {
  def apply[T <: Data](gen: T) = new SimReadyValidIO(gen)
}

class ReadyValidTraceIO[T <: Data](gen: T) extends Bundle {
  val bits = Decoupled(gen)
  val valid = Decoupled(Bool())
  val ready = Decoupled(Bool())
  override def cloneType = new ReadyValidTraceIO(gen).asInstanceOf[this.type]
}

object ReadyValidTrace {
  def apply[T <: Data](gen: T) = new ReadyValidTraceIO(gen)
}

class ReadyValidChannelIO[T <: Data](gen: T)(implicit p: Parameters) extends Bundle {
  val enq = Flipped(SimReadyValid(gen))
  val deq = SimReadyValid(gen)
  val trace = ReadyValidTrace(gen)
  val traceLen = Input(UInt(log2Up(p(TraceMaxLen)+1).W))
  val targetReset = Flipped(Decoupled(Bool()))
  override def cloneType = new ReadyValidChannelIO(gen)(p).asInstanceOf[this.type]
}

class ReadyValidChannel[T <: Data](gen: T, flipped: Boolean, n: Int = 2)(implicit p: Parameters) extends Module {
  val io = IO(new ReadyValidChannelIO(gen))
  val target = Module(new Queue(gen, n))
  val tokens = Module(new Queue(Bool(), p(ChannelLen))) // keep enq handshakes

  target.reset := io.targetReset.bits && io.targetReset.valid
  io.targetReset.ready := true.B // TODO: is it ok?

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

  if (p(EnableSnapshot)) {
    val wires = Wire(ReadyValidTrace(gen))
    val targetFace = if (flipped) io.deq.target else io.enq.target
    val tokensFace = if (flipped) tokens.io.deq else tokens.io.enq
    val readyTraceFull = Wire(Bool())
    val validTraceFull = Wire(Bool())
    wires.bits.bits  := targetFace.bits
    wires.bits.valid := tokensFace.valid && targetFace.valid && !readyTraceFull && !validTraceFull
    wires.bits.ready := tokensFace.ready
    io.trace.bits <> TraceQueue(wires.bits, io.traceLen, "bits_trace")
    wires.valid.bits  := targetFace.valid
    wires.valid.valid := tokensFace.valid
    wires.valid.ready := tokensFace.ready
    io.trace.valid <> TraceQueue(wires.valid, io.traceLen, "valid_trace", Some(validTraceFull))
    wires.ready.bits  := targetFace.ready
    wires.ready.valid := tokensFace.valid
    wires.ready.ready := tokensFace.ready
    io.trace.ready <> TraceQueue(wires.ready, io.traceLen, "ready_trace", Some(readyTraceFull))
  } else {
    io.trace.bits.valid  := Bool(false)
    io.trace.valid.valid := Bool(false)
    io.trace.ready.valid := Bool(false)
  }
}
