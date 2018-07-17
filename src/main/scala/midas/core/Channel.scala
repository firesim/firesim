// See LICENSE for license details.

package midas
package core

import strober.core.{TraceQueue, TraceMaxLen}
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.util.ParameterizedBundle

import chisel3._
import chisel3.util._

// For now use the convention that clock ratios are set with respect to the transformed RTL
trait IsRationalClockRatio {
  def numerator: Int
  def denominator: Int
  def isUnity() = numerator == denominator
  def isReciprocal() = numerator == 1
  def isIntegral() =  denominator == 1
  def inverse: IsRationalClockRatio
}

case class RationalClockRatio(numerator: Int, denominator: Int) extends IsRationalClockRatio {
  def inverse() = RationalClockRatio(denominator, numerator)
}

case object UnityClockRatio extends IsRationalClockRatio {
  val numerator = 1
  val denominator = 1
  def inverse() = UnityClockRatio
}

case class ReciprocalClockRatio(denominator: Int) extends IsRationalClockRatio {
  val numerator = 1
  def inverse = IntegralClockRatio(numerator = denominator)
}

case class IntegralClockRatio(numerator: Int) extends IsRationalClockRatio {
  val denominator = 1
  def inverse = ReciprocalClockRatio(denominator = numerator)
}

class WireChannelIO(w: Int)(implicit p: Parameters) extends Bundle {
  val in    = Flipped(Decoupled(UInt(w.W)))
  val out   = Decoupled(UInt(w.W))
  val trace = Decoupled(UInt(w.W))
  val traceLen = Input(UInt(log2Up(p(TraceMaxLen)+1).W))
  override def cloneType = new WireChannelIO(w)(p).asInstanceOf[this.type]
}

class WireChannel(
    val w: Int,
    clockRatio: IsRationalClockRatio = UnityClockRatio
  )(implicit p: Parameters) extends Module {

  require(clockRatio.isReciprocal || clockRatio.isIntegral)

  val io = IO(new WireChannelIO(w))
  val tokens = Module(new Queue(UInt(w.W), p(ChannelLen)))
  tokens.io.enq <> io.in
  io.out <> tokens.io.deq

  // Dequeuing domain is faster; duplicate tokens by dequeuing from the token
  // queue every N handshakes
  if (clockRatio.isIntegral && !clockRatio.isUnity) {
    val deqTokenCount = RegInit((clockRatio.numerator - 1).U(log2Ceil(clockRatio.numerator).W))
    deqTokenCount.suggestName("deqTokenCount")
    tokens.io.deq.ready := false.B
    when (io.out.fire && deqTokenCount =/= 0.U) {
      deqTokenCount := deqTokenCount - 1.U
    }.elsewhen(io.out.fire && deqTokenCount === 0.U) {
      deqTokenCount := (clockRatio.numerator - 1).U
      tokens.io.deq.ready := true.B
    }
  // Dequeuing domain is slower; drop enqueued tokens by ignoring M-1 enqueue handshakes
  } else if (clockRatio.isReciprocal && !clockRatio.isUnity) {
    val enqTokenCount = RegInit(0.U(log2Ceil(clockRatio.denominator).W))
    enqTokenCount.suggestName("enqTokenCount")
    tokens.io.enq.valid := false.B
    when (io.in.fire && enqTokenCount =/= 0.U) {
      enqTokenCount := enqTokenCount - 1.U
    }.elsewhen(io.in.fire && enqTokenCount === 0.U) {
      enqTokenCount := (clockRatio.denominator - 1).U
      tokens.io.enq.valid := true.B
    }
  }

  if (p(EnableSnapshot)) {
    io.trace <> TraceQueue(tokens.io.deq, io.traceLen)
  } else {
    io.trace := DontCare
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

class ReadyValidChannel[T <: Data](
    gen: T,
    flipped: Boolean,
    n: Int = 2, // Target queue depth
    // Clock ratio (N/M) of deq interface (N) vs enq interface (M)
    clockRatio: IsRationalClockRatio = UnityClockRatio
  )(implicit p: Parameters) extends Module {
  require(clockRatio.isReciprocal || clockRatio.isIntegral)

  val io = IO(new ReadyValidChannelIO(gen))
  // Stores tokens with valid target-data that have been successfully enqueued
  val target = Module(new Queue(gen, n))
  // Stores a bit indicating if a given token contained valid target-data
  // 1 = there was a target handshake; 0 = no target handshake
  val tokens = Module(new Queue(Bool(), p(ChannelLen)))

  target.reset := io.targetReset.bits && io.targetReset.valid
  io.targetReset.ready := true.B // TODO: is it ok?

  target.io.enq.bits := io.enq.target.bits
  target.io.enq.valid := io.enq.target.valid && io.enq.host.hValid
  io.enq.target.ready := target.io.enq.ready
  io.enq.host.hReady := tokens.io.enq.ready
  tokens.io.enq.bits := target.io.enq.fire()
  tokens.io.enq.valid := io.enq.host.hValid

  // Track the number of tokens with valid target-data that should be visible
  // to the dequeuer. This allows the enq-side model to advance ahead of the deq-side model
  val numTValid = RegInit(0.U(8.W))
  val tValid = tokens.io.deq.bits || numTValid =/= 0.U
  val newTValid = tokens.io.deq.fire && tokens.io.deq.bits
  val tValidConsumed = io.deq.host.fire && io.deq.target.fire

  io.deq.target.bits := target.io.deq.bits
  io.deq.target.valid := target.io.deq.valid && tValid
  target.io.deq.ready := io.deq.target.ready && tValid && io.deq.host.fire

  when(newTValid && !tValidConsumed) {
    numTValid := numTValid + 1.U
  }.elsewhen(!newTValid && tValidConsumed) {
    numTValid := numTValid - 1.U
  }

  // Enqueuing and dequeuing domains have the same frequency
  // The token queue can be directly coupled between domains
  if (clockRatio.isUnity) {
    io.deq.host.hValid := tokens.io.deq.valid
    tokens.io.deq.ready := io.deq.host.hReady
  }
  // Dequeuing domain is faster
  // Each token in the "token" queue represents a token in the slow domain
  // Issue N output tokens per entry in the token queue
  else if (clockRatio.isIntegral) {
    val deqTokenCount = RegInit((clockRatio.numerator - 1).U(log2Ceil(clockRatio.numerator).W))
    deqTokenCount.suggestName("deqTokenCount")
    tokens.io.deq.ready := false.B
    io.deq.host.hValid := tokens.io.deq.valid

    when (io.deq.host.fire && deqTokenCount =/= 0.U) {
      deqTokenCount := deqTokenCount - 1.U
    }.elsewhen(io.deq.host.fire && deqTokenCount === 0.U) {
      deqTokenCount := (clockRatio.numerator - 1).U
      tokens.io.deq.ready := true.B
    }
  }
  // Dequeuing domain is slower
  // Each entry in the "token" queue represents a token in the slow domain
  // Every M tokens received in the fast domain, enqueue a single entry into the "tokens" queue
  else if (clockRatio.isReciprocal) {
    val enqTokensRemaining = RegInit((clockRatio.denominator - 1).U(log2Ceil(clockRatio.denominator).W))
    enqTokensRemaining.suggestName("enqTokensRemaining")
    tokens.io.deq.ready := enqTokensRemaining =/= 0.U || io.deq.host.hReady
    io.deq.host.hValid := tokens.io.deq.valid && enqTokensRemaining === 0.U

    when (tokens.io.deq.fire) {
      enqTokensRemaining := Mux(enqTokensRemaining === 0.U,
                                (clockRatio.denominator - 1).U,
                                enqTokensRemaining - 1.U)
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
    io.trace := DontCare
    io.trace.bits.valid  := Bool(false)
    io.trace.valid.valid := Bool(false)
    io.trace.ready.valid := Bool(false)
  }
}
