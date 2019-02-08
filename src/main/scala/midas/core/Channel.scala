// See LICENSE for license details.

package midas
package core

import freechips.rocketchip.config.Parameters
import freechips.rocketchip.unittest._
import freechips.rocketchip.tilelink.LFSR64 // Better than chisel's

import chisel3._
import chisel3.util._

import strober.core.{TraceQueue, TraceMaxLen}

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
  require(p(ChannelLen) >= clockRatio.denominator)

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
    io.trace.valid := false.B
  }
}

class WireChannelUnitTest(
    clockRatio: IsRationalClockRatio = UnityClockRatio,
    numTokens: Int = 4096,
    timeout: Int = 50000
  )(implicit p: Parameters) extends UnitTest(timeout) {

  require(clockRatio.isReciprocal || clockRatio.isIntegral)
  override val testName = "WireChannel ClockRatio: ${clockRatio.numerator}/${clockRatio.denominator}"

  val payloadWidth = numTokens * clockRatio.numerator

  val dut = Module(new WireChannel(payloadWidth, clockRatio))
  val inputTokenNum       = RegInit(0.U(payloadWidth.W))
  val outputTokenNum      = RegInit(0.U(payloadWidth.W))
  val expectedOutputToken = RegInit(0.U(payloadWidth.W))
  val outputDuplicatesRemaining = RegInit((clockRatio.numerator - 1).U)

  val outputReadyFuzz = LFSR64()(0)
  val inputValidFuzz = LFSR64()(0)

  val finished = RegInit(false.B)

  dut.io.in.bits := inputTokenNum
  dut.io.in.valid  := inputValidFuzz
  dut.io.out.ready := outputReadyFuzz && !finished

  dut.io.traceLen := DontCare
  dut.io.trace.ready := DontCare


  when (dut.io.in.fire) {
    inputTokenNum := inputTokenNum + 1.U
  }

  when (dut.io.out.fire) {
    assert(finished || dut.io.out.bits === expectedOutputToken, "Output token does not match expected value")

    outputTokenNum := outputTokenNum + 1.U
    outputDuplicatesRemaining := Mux(outputDuplicatesRemaining === 0.U,
                                     (clockRatio.numerator-1).U,
                                     outputDuplicatesRemaining - 1.U)
    if (clockRatio.isIntegral) {
      expectedOutputToken := Mux(outputDuplicatesRemaining === 0.U,
                                 expectedOutputToken + 1.U,
                                 expectedOutputToken)
    } else {
      expectedOutputToken := expectedOutputToken + clockRatio.denominator.U
    }

    finished := finished || outputTokenNum === (numTokens-1).U
  }

  io.finished := finished
}

class SimReadyValidIO[T <: Data](gen: T) extends Bundle {
  val target = EnqIO(gen)
  val host = new HostReadyValid
  override def cloneType = new SimReadyValidIO(gen).asInstanceOf[this.type]

  def fwdIrrevocabilityAssertions(suggestedName: Option[String] = None): Unit = {
    val hValidPrev = RegNext(host.hValid, false.B)
    val hReadyPrev = RegNext(host.hReady)
    val hFirePrev = hValidPrev && hReadyPrev
    val tPrev = RegNext(target)
    val prefix = suggestedName match {
      case Some(name) => name + ": "
      case None => ""
    }
    assert(!hValidPrev || hFirePrev || host.hValid,
      s"${prefix}hValid de-asserted without handshake, violating fwd token irrevocability")
    assert(!hValidPrev || hFirePrev || tPrev.valid === target.valid,
      s"${prefix}tValid transitioned without host handshake, violating fwd token irrevocability")
    assert(!hValidPrev || hFirePrev || tPrev.bits.asUInt() === target.bits.asUInt(),
      s"${prefix}tBits transitioned without host handshake, violating fwd token irrevocability")
    assert(!hFirePrev  || tPrev.fire || !tPrev.valid,
      s"${prefix}tValid deasserted without prior target handshake, violating target-queue irrevocability")
    assert(!hFirePrev  || tPrev.fire || !tPrev.valid || tPrev.bits.asUInt() === target.bits.asUInt(),
      s"${prefix}tBits transitioned without prior target handshake, violating target-queue irrevocability")
  }

  def revIrrevocabilityAssertions(suggestedName: Option[String] = None): Unit = {
    val prefix = suggestedName match {
      case Some(name) => name + ": "
      case None => ""
    }
    val hReadyPrev = RegNext(host.hReady, false.B)
    val hValidPrev = RegNext(host.hValid)
    val tReadyPrev = RegNext(target.ready)
    val hFirePrev = hReadyPrev && hValidPrev
    assert(hFirePrev || !hReadyPrev || host.hReady,
      s"${prefix}hReady de-asserted, violating token irrevocability")
    assert(hFirePrev || !hReadyPrev || tReadyPrev === target.ready,
      s"${prefix}tReady de-asserted, violating token irrevocability")
  }
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

abstract class ReadyValidChannel[T <: Data](gen: T)(implicit p: Parameters) extends Module {
  val io = IO(new ReadyValidChannelIO(gen))
}

object ReadyValidChannel {
  def apply[T <: Data](
    gen: T,
    flipped: Boolean,
    n: Int = 2,
    clockRatio: IsRationalClockRatio = UnityClockRatio)(implicit p: Parameters): ReadyValidChannel[T] = {
    if (clockRatio.isUnity) {
      Module(new SyncReadyValidChannel(gen, flipped, n))
    } else {
      Module(new CDCReadyValidChannel(gen, flipped, n, clockRatio))
    }
  }
}
class SyncReadyValidChannel[T <: Data](
    gen: T,
    flipped: Boolean,
    n: Int = 2, // Target queue depth
    // Clock ratio (N/M) of deq interface (N) vs enq interface (M)
    clockRatio: IsRationalClockRatio = UnityClockRatio
  )(implicit p: Parameters) extends ReadyValidChannel(gen)(p) {
  require(clockRatio.isReciprocal || clockRatio.isIntegral)
  require(p(ChannelLen) > 1)
  require(n > 1, "Single entry RVChannels have not been tested.")

  // Stores tokens with valid target-data that have been successfully enqueued
  val target = Module(new Queue(gen, n))
  // Stores a bit indicating if a given token contained valid target-data
  // 1 = there was a target handshake; 0 = no target handshake
  val tokens = Module(new Queue(Bool(), p(ChannelLen)))

  target.reset := io.targetReset.bits && io.targetReset.valid
  io.targetReset.ready := true.B // TODO: is it ok?

  // If there are no token available, the deq-side has advanced ahead 1 (local) target-cycle
  val deqAhead = tokens.io.count === 0.U
  // If there are N > 1 tokens available, the enq has advanced ahead N-1 (local) target-cycles
  val enqAhead = tokens.io.count  > 1.U
  val coupled = !deqAhead && !enqAhead

  target.io.enq.bits  := io.enq.target.bits
  target.io.enq.valid := io.enq.host.fire && io.enq.target.fire
  tokens.io.enq.bits  := io.enq.target.fire
  tokens.io.enq.valid := io.enq.host.fire // Warning: unneeded dependency on tokens.io.enq.ready

  // Track the number of tokens with valid target-data that should be visible
  // to the dequeuer. This allows the enq-side model to advance ahead of the deq-side model
  val numTValid = RegInit(0.U(log2Ceil(n+1).W))
  val tValid = tokens.io.deq.bits || numTValid =/= 0.U
  val newTValid = tokens.io.deq.fire && tokens.io.deq.bits
  val tValidConsumed = io.deq.host.fire && io.deq.target.fire

  // The deq-domain can also advance ahead of the enq domain by a cycle; record if there was a target-land
  // handshake, so we can expose the right target-ready value in the enq-ready token
  val tQWasFull = RegEnable(!target.io.enq.ready, false.B, io.deq.host.fire)
  // The only case where we don't know the value of tReady (and thus, can't
  // assert hReady), is when the enq domain has slipped ahead and the target
  // queue is full. When the deq domain advances, it may dequeue some entries
  io.enq.host.hReady  := !(enqAhead && !target.io.enq.ready)
  // tReady can always be asserted except for two cases
  // 1) the deq domain is ahead, and the target queue was full on the previous deq-cycle
  // 2) the domains are in the same cycle and the target queue is full
  io.enq.target.ready := !((deqAhead && tQWasFull) ||
                           (coupled  && !target.io.enq.ready))

  // The enq domain is easier. Dequeue target-valid tokens on handshakes
  target.io.deq.ready := io.deq.target.ready && tValid && io.deq.host.fire
  io.deq.target.bits  := target.io.deq.bits
  // Don't present valid target-payloads that were enqueued in the "future" (when the
  // enq domain has slipped ahead) (see tValid)
  io.deq.target.valid := target.io.deq.valid && tValid

  when(newTValid && !tValidConsumed) {
    numTValid := numTValid + 1.U
  }.elsewhen(!newTValid && tValidConsumed) {
    numTValid := numTValid - 1.U
  }

  // Enqueuing and dequeuing domains have the same frequency
  // The token queue can be directly coupled between domains
  io.deq.host.hValid := tokens.io.deq.valid
  tokens.io.deq.ready := io.deq.host.hReady

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
    io.trace.bits.valid  := false.B
    io.trace.valid.valid := false.B
    io.trace.ready.valid := false.B
  }
}
class CDCReadyValidChannel[T <: Data](
    gen: T,
    flipped: Boolean,
    n: Int = 2, // Target queue depth
    // Clock ratio (N/M) of deq interface (N) vs enq interface (M)
    clockRatio: IsRationalClockRatio
  )(implicit p: Parameters) extends ReadyValidChannel(gen)(p) {
  require(!clockRatio.isUnity)

  // Stores tokens with valid target-data that have been successfully enqueued
  val target = Module(new Queue(gen, n))
  // Stores a bit indicating if a given token contained valid target-data
  // 1 = there was a target handshake; 0 = no target handshake
  val tokens = Module(new Queue(Bool(), p(ChannelLen)))

  target.reset := io.targetReset.bits && io.targetReset.valid
  io.targetReset.ready := true.B // TODO: is it ok?

  target.io.enq.bits := io.enq.target.bits
  target.io.enq.valid := io.enq.target.valid && io.enq.host.fire
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
  // Dequeuing domain is faster
  // Each token in the "token" queue represents a token in the slow domain
  // Issue N output tokens per entry in the token queue
  if (clockRatio.isIntegral) {
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
  io.trace := DontCare
  io.trace.bits.valid  := false.B
  io.trace.valid.valid := false.B
  io.trace.ready.valid := false.B
}


class ReadyValidChannelUnitTest(
    clockRatio: IsRationalClockRatio = UnityClockRatio,
    numNonEmptyTokens: Int = 2048,
    timeout: Int = 50000
  )(implicit p: Parameters) extends UnitTest(timeout) {

  require(clockRatio.isReciprocal || clockRatio.isIntegral)
  override val testName = "WireChannel ClockRatio: ${clockRatio.numerator}/${clockRatio.denominator}"

  val payloadWidth = log2Ceil(numNonEmptyTokens + 1)

  val dut = if (clockRatio.isUnity) {
    val syncRVCh = Module(new SyncReadyValidChannel(UInt(payloadWidth.W), flipped = false))
    // Check that the queue's backpressure is queue-like
    syncRVCh.io.enq.revIrrevocabilityAssertions(Some("Decoupled Channel Enq"))
    syncRVCh
  } else {
    Module(new CDCReadyValidChannel(UInt(payloadWidth.W), flipped = false, clockRatio = clockRatio))
  }
  // Count host-level handshakes on tokens
  val inputTokenNum      = RegInit(0.U(log2Ceil(timeout).W))
  val outputTokenNum     = RegInit(0.U(log2Ceil(timeout).W))

  // For driving the values of non-empty tokens
  val inputTokenPayload   = RegInit(0.U(payloadWidth.W))
  val expectedOutputPayload = RegInit(0.U(payloadWidth.W))

  val outputHReadyFuzz = LFSR64()(0)
  val outputTReadyFuzz = LFSR64()(0)
  val inputHValidFuzz = LFSR64()(0)
  val inputTValidFuzz = LFSR64()(0)

  val finished = RegInit(false.B)

  dut.io.enq.host.hValid := inputHValidFuzz
  dut.io.enq.target.valid  := inputTValidFuzz
  dut.io.enq.target.bits := inputTokenPayload

  dut.io.deq.host.hReady := outputHReadyFuzz && !finished
  dut.io.deq.target.ready := outputTReadyFuzz && !finished

  dut.io.traceLen := DontCare
  dut.io.trace.ready := DontCare


  when (dut.io.enq.host.fire) {
    inputTokenNum := inputTokenNum + 1.U

    when ( dut.io.enq.target.fire) {
      inputTokenPayload := inputTokenPayload + 1.U
    }
  }

  val (lowerTokenBound, upperTokenBound) = if (clockRatio.isIntegral) {
    val lB = Mux( inputTokenNum > p(ChannelLen).U,
                 (inputTokenNum - p(ChannelLen).U)  * clockRatio.numerator.U,
                  0.U)
    val uB = inputTokenNum * clockRatio.numerator.U
    (lB, uB)
  } else {
    // The channel requires only a single input token after reset to produce its output token
    val uB = (inputTokenNum + (clockRatio.denominator - 1).U) / clockRatio.denominator.U 
    val lB = Mux(uB > p(ChannelLen).U,
                 uB - p(ChannelLen).U,
                 0.U)
    (lB, uB)
  }

  when (dut.io.deq.host.fire) {
    outputTokenNum := outputTokenNum + 1.U

    assert(finished || outputTokenNum <= upperTokenBound, "Received too many output tokens.")
    assert(finished || outputTokenNum >= lowerTokenBound, "Received too few output tokens.")

    // Check the target-data
    when (dut.io.deq.target.fire) {
      assert(finished || dut.io.deq.target.bits === expectedOutputPayload, "Output token does not match expected value")
      expectedOutputPayload := expectedOutputPayload + 1.U //clockRatio.denominator.U

      finished := finished || expectedOutputPayload === (numNonEmptyTokens - 1).U
    }
  }

  io.finished := finished

  dut.io.traceLen := DontCare
  dut.io.traceLen := DontCare
  // TODO: FIXME
  dut.io.targetReset.valid := reset.toBool()
  dut.io.targetReset.bits := reset.toBool()
  dut.io.trace.ready.ready := DontCare
  dut.io.trace.valid.ready := DontCare
  dut.io.trace.bits.ready := DontCare
  dut.io.traceLen := DontCare
}
