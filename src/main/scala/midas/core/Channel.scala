// See LICENSE for license details.

package midas
package core

import freechips.rocketchip.config.Parameters
import freechips.rocketchip.unittest._
import freechips.rocketchip.util.{DecoupledHelper}
import freechips.rocketchip.tilelink.LFSR64 // Better than chisel's

import chisel3._
import chisel3.util._

import strober.core.{TraceQueue, TraceMaxLen}
import midas.core.SimUtils.{ChLeafType}

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

class WireChannelIO[T <: ChLeafType](gen: T)(implicit p: Parameters) extends Bundle {
  val in    = Flipped(Decoupled(gen))
  val out   = Decoupled(gen)
  val trace = Decoupled(gen)
  val traceLen = Input(UInt(log2Up(p(TraceMaxLen)+1).W))
  override def cloneType = new WireChannelIO(gen)(p).asInstanceOf[this.type]

}

class WireChannel[T <: ChLeafType](
    val gen: T,
    clockRatio: IsRationalClockRatio = UnityClockRatio
  )(implicit p: Parameters) extends Module {

  require(clockRatio.isReciprocal || clockRatio.isIntegral)
  require(p(ChannelLen) >= clockRatio.denominator)

  val io = IO(new WireChannelIO(gen))
  val tokens = Module(new Queue(gen, p(ChannelLen)))
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

  val dut = Module(new WireChannel(UInt(payloadWidth.W), clockRatio))
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

// A bidirectional token channel wrapping a target-decoupled (ready-valid) interface
// Structurally, this keeps the target bundle intact however it should really be thought of as:
// two *independent*token channels
// fwd: DecoupledIO (carries a combined valid-and-payload token)
//  - valid -> fwd.hValid
//  - ready -> fwd.hReady
//  - bits  -> {target.valid, target.bits}
//
// rev: DecoupledIO (carries a ready token)
//  - valid -> rev.hValid
//  - ready -> rev.hReady
//  - bits  -> target.ready
//
// WARNING: Target.fire() is meaningless unless are fwd and rev channels are
// synchronized and carry valid tokens

class SimReadyValidIO[T <: Data](gen: T) extends Bundle {
  val target = EnqIO(gen)
  val fwd = new HostReadyValid
  val rev = Flipped(new HostReadyValid)
  override def cloneType = new SimReadyValidIO(gen).asInstanceOf[this.type]

  def fwdIrrevocabilityAssertions(suggestedName: Option[String] = None): Unit = {
    val hValidPrev = RegNext(fwd.hValid, false.B)
    val hReadyPrev = RegNext(fwd.hReady)
    val hFirePrev = hValidPrev && hReadyPrev
    val tPrev = RegNext(target)
    val prefix = suggestedName match {
      case Some(name) => name + ": "
      case None => ""
    }
    assert(!hValidPrev || hFirePrev || fwd.hValid,
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
    val hReadyPrev = RegNext(rev.hReady, false.B)
    val hValidPrev = RegNext(rev.hValid)
    val tReadyPrev = RegNext(target.ready)
    val hFirePrev = hReadyPrev && hValidPrev
    assert(hFirePrev || !hReadyPrev || rev.hReady,
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

class ReadyValidChannel[T <: Data](
    gen: T,
    n: Int = 2, // Target queue depth
    // Clock ratio (N/M) of deq interface (N) vs enq interface (M)
    clockRatio: IsRationalClockRatio = UnityClockRatio
  )(implicit p: Parameters) extends Module {
  require(clockRatio.isUnity, "CDC is not currently implemented")

  val io = IO(new ReadyValidChannelIO(gen))
  // Stores tokens with valid target-data that have been successfully enqueued
  val target = Module(new Queue(gen, n))
  // Stores a bit indicating if a given token contained valid target-data
  // 1 = there was a target handshake; 0 = no target handshake
  val tokens = Module(new Queue(Bool(), p(ChannelLen)))

  target.reset := reset.toBool || io.targetReset.bits && io.targetReset.valid
  io.targetReset.ready := true.B // TODO: is it ok?

  // FIXME: This couples fwd and rev token channels
  val dummy = true.B

  val enqFwdHelper = DecoupledHelper(
    tokens.io.enq.ready,
    io.enq.fwd.hValid,
    dummy)

  // ENQ DOMAIN LOGIC
  // Enq-fwd token control
  target.io.enq.valid := enqFwdHelper.fire(dummy) & io.enq.target.valid
  target.io.enq.bits  := io.enq.target.bits
  tokens.io.enq.valid := enqFwdHelper.fire(tokens.io.enq.ready)
  tokens.io.enq.bits  := io.enq.target.valid && target.io.enq.ready

  // Consume the enq-fwd token if:
  //  1) the target queue isn't empty -> enq and deq sides can decouple in target-time 
  //  2) the target queue is full but token queue is empty -> enq & deq sides have lost decoupling
  //     and we know this payload will not be enqueued in target-land
  io.enq.fwd.hReady := target.io.enq.ready || !tokens.io.deq.valid

  // Enq-rev token control
  // Assumptions: model a queue with flow = false
  // Simplifications: Only let rev-tokens slip behind fwd-tokens for now.

  // Capture ready tokens that would be dropped
  val readyTokens = Module(new Queue(Bool(), n, flow = false))
  readyTokens.io.enq.valid := enqFwdHelper.fire(dummy) && !io.enq.rev.hReady
  readyTokens.io.enq.bits  := target.io.enq.ready

  io.enq.rev.hValid   := true.B
  io.enq.target.ready := Mux(readyTokens.io.deq.valid, readyTokens.io.deq.bits, target.io.enq.ready)
  readyTokens.io.deq.ready := io.enq.rev.hReady

  // Check simplificaiton
  assert(!readyTokens.io.enq.valid || !io.enq.rev.fire || io.enq.fwd.fire,
    "Enq-rev channel would advance ahead of enq-fwd channel")

  // DEQ-DOMAIN LOGIC
  // Track the number of tokens with valid target-data that should be visible
  // to the dequeuer. This allows the enq-side model to advance ahead of the deq-side model
  val numTValid = RegInit(0.U(log2Ceil(p(ChannelLen) + 1).W))
  val tValid = tokens.io.deq.bits || numTValid =/= 0.U
  val newTValid = tokens.io.deq.fire && tokens.io.deq.bits

  // Simplification: only let the deq-rev tokens slip ahead of deq-fwd tokens
  // We let this happen since the LI-BDN fame pass consumes all of its input tokens
  // simultaenously as the last rule to fire
  val deqRevSlip  = RegInit(false.B) // True if deq-rev tokens have decoupled from deq-fwd
  val deqRevLast = RegInit(false.B)  // The last deq-rev token target-ready, if slipped

  tokens.io.deq.ready := io.deq.fwd.hReady
  io.deq.fwd.hValid := tokens.io.deq.valid
  io.deq.target.valid := tValid
  io.deq.rev.hReady := !deqRevSlip
  io.deq.target.bits  := target.io.deq.bits // fixme: exercise more caution when exposing bits field

  val tValidConsumed = io.deq.fwd.fire && tValid &&
                      (io.deq.rev.hValid && io.deq.target.ready ||
                       deqRevSlip && deqRevLast)

  target.io.deq.ready := tValidConsumed

  when (io.deq.rev.fire && !io.deq.fwd.fire) {
    deqRevSlip  := true.B
    deqRevLast  := io.deq.target.ready
  }.elsewhen(io.deq.fwd.fire) {
    deqRevSlip  := false.B
  }

  when(newTValid && !tValidConsumed) {
    numTValid := numTValid + 1.U
  }.elsewhen(!newTValid && tValidConsumed) {
    numTValid := numTValid - 1.U
  }

  // Enqueuing and dequeuing domains have the same frequency
  // The token queue can be directly coupled between domains
  //if (clockRatio.isUnity) {
  //  io.deq.fwd.hValid := deqHelper.fire(io.deq.fwd.hReady)
  //  io.deq.rev.hReady := deqHelper.fire(io.deq.rev.hValid)
  //  tokens.io.deq.ready := deqHelper.fire(tokens.io.deq.valid)
  //}
  // Dequeuing domain is faster
  // Each token in the "token" queue represents a token in the slow domain
  // Issue N output tokens per entry in the token queue
  //else if (clockRatio.isIntegral) {
  //  val deqTokenCount = RegInit((clockRatio.numerator - 1).U(log2Ceil(clockRatio.numerator).W))
  //  deqTokenCount.suggestName("deqTokenCount")
  //  tokens.io.deq.ready := false.B
  //  io.deq.host.hValid := tokens.io.deq.valid

  //  when (io.deq.host.fire && deqTokenCount =/= 0.U) {
  //    deqTokenCount := deqTokenCount - 1.U
  //  }.elsewhen(io.deq.host.fire && deqTokenCount === 0.U) {
  //    deqTokenCount := (clockRatio.numerator - 1).U
  //    tokens.io.deq.ready := true.B
  //  }
  //}
  // Dequeuing domain is slower
  // Each entry in the "token" queue represents a token in the slow domain
  // Every M tokens received in the fast domain, enqueue a single entry into the "tokens" queue
  //else if (clockRatio.isReciprocal) {
  //  val enqTokensRemaining = RegInit((clockRatio.denominator - 1).U(log2Ceil(clockRatio.denominator).W))
  //  enqTokensRemaining.suggestName("enqTokensRemaining")
  //  tokens.io.deq.ready := enqTokensRemaining =/= 0.U || io.deq.host.hReady
  //  io.deq.host.hValid := tokens.io.deq.valid && enqTokensRemaining === 0.U

  //  when (tokens.io.deq.fire) {
  //    enqTokensRemaining := Mux(enqTokensRemaining === 0.U,
  //                              (clockRatio.denominator - 1).U,
  //                              enqTokensRemaining - 1.U)
  //  }
  //}


  //if (p(EnableSnapshot)) {
  //  val wires = Wire(ReadyValidTrace(gen))
  //  val targetFace = if (flipped) io.deq.target else io.enq.target
  //  val tokensFace = if (flipped) tokens.io.deq else tokens.io.enq
  //  val readyTraceFull = Wire(Bool())
  //  val validTraceFull = Wire(Bool())
  //  wires.bits.bits  := targetFace.bits
  //  wires.bits.valid := tokensFace.valid && targetFace.valid && !readyTraceFull && !validTraceFull
  //  wires.bits.ready := tokensFace.ready
  //  io.trace.bits <> TraceQueue(wires.bits, io.traceLen, "bits_trace")
  //  wires.valid.bits  := targetFace.valid
  //  wires.valid.valid := tokensFace.valid
  //  wires.valid.ready := tokensFace.ready
  //  io.trace.valid <> TraceQueue(wires.valid, io.traceLen, "valid_trace", Some(validTraceFull))
  //  wires.ready.bits  := targetFace.ready
  //  wires.ready.valid := tokensFace.valid
  //  wires.ready.ready := tokensFace.ready
  //  io.trace.ready <> TraceQueue(wires.ready, io.traceLen, "ready_trace", Some(readyTraceFull))
  //} else {
  io.trace := DontCare
  io.trace.bits.valid  := false.B
  io.trace.valid.valid := false.B
  io.trace.ready.valid := false.B
  //}
}

class ReadyValidChannelUnitTest(
    clockRatio: IsRationalClockRatio = UnityClockRatio,
    numNonEmptyTokens: Int = 2048,
    timeout: Int = 50000
  )(implicit p: Parameters) extends UnitTest(timeout) {

  // TODO: FIXME
  io.finished := true.B
  //require(clockRatio.isReciprocal || clockRatio.isIntegral)
  //override val testName = "WireChannel ClockRatio: ${clockRatio.numerator}/${clockRatio.denominator}"

  //val payloadWidth = log2Ceil(numNonEmptyTokens + 1)

  //val dut = Module(new ReadyValidChannel(UInt(payloadWidth.W), flipped = false, clockRatio = clockRatio))
  //// Count host-level handshakes on tokens
  //val inputTokenNum      = RegInit(0.U(log2Ceil(timeout).W))
  //val outputTokenNum     = RegInit(0.U(log2Ceil(timeout).W))

  //val enqTReadyCredits = Module(new Queue(new DecoupledIO(Bool()), p(ChannelLen), flow = true))
  //enqTReadyCredits.io.enq.valid := dut.io.enq.rev.fire
  //enqTReadyCredits.io.enq.bits  := dut.io.enq.target.ready

  //val deqTReadyCredits = Module(new Queue(new DecoupledIO(Bool()), p(ChannelLen), flow = true))
  //deqTReadyCredits.io.enq.valid := dut.io.deq.rev.fire
  //deqTReadyCredits.io.enq.bits  := dut.io.deq.target.ready


  //// For driving the values of non-empty tokens
  //val inputTokenPayload   = RegInit(0.U(payloadWidth.W))
  //val expectedOutputPayload = RegInit(0.U(payloadWidth.W))

  //val outputHReadyFuzz = LFSR64()(0) // Payload & valid token ready
  //val outputHValidFuzz = LFSR64()(0) // Ready token valid
  //val outputTReadyFuzz = LFSR64()(0) // Ready token value
  //val inputHValidFuzz  = LFSR64()(0) // Payload & valid token valid
  //val inputTValidFuzz  = LFSR64()(0) // Valid-token value
  //val inputHReadyFuzz  = LFSR64()(0) // Ready-token ready

  //val finished = RegInit(false.B)

  //dut.io.enq.fwd.hValid    := inputHValidFuzz
  //dut.io.enq.rev.hReady    := inputHReadyFuzz
  //dut.io.enq.target.valid  := inputTValidFuzz
  //dut.io.enq.target.bits   := inputTokenPayload

  //dut.io.deq.fwd.hReady   := outputHReadyFuzz && !finished
  //dut.io.deq.rev.hValid   := outputHValidFuzz && !finished
  //dut.io.deq.target.ready := outputTReadyFuzz && !finished

  //dut.io.traceLen := DontCare
  //dut.io.trace.ready := DontCare

  //when (dut.io.enq.fwd.fire) {
  //  inputTokenNum := inputTokenNum + 1.U

  //  when ( dut.io.enq.target.fire) {
  //    inputTokenPayload := inputTokenPayload + 1.U
  //  }
  //}

  //val (lowerTokenBound, upperTokenBound) = if (clockRatio.isIntegral) {
  //  val lB = Mux( inputTokenNum > p(ChannelLen).U,
  //               (inputTokenNum - p(ChannelLen).U)  * clockRatio.numerator.U,
  //                0.U)
  //  val uB = inputTokenNum * clockRatio.numerator.U
  //  (lB, uB)
  //} else {
  //  // The channel requires only a single input token after reset to produce its output token
  //  val uB = (inputTokenNum + (clockRatio.denominator - 1).U) / clockRatio.denominator.U 
  //  val lB = Mux(uB > p(ChannelLen).U,
  //               uB - p(ChannelLen).U,
  //               0.U)
  //  (lB, uB)
  //}

  //when (dut.io.deq.fwd.fire) {
  //  outputTokenNum := outputTokenNum + 1.U

  //  assert(finished || outputTokenNum <= upperTokenBound, "Received too many output tokens.")
  //  assert(finished || outputTokenNum >= lowerTokenBound, "Received too few output tokens.")

  //  // Check the target-data
  //  when (dut.io.deq.fwdtarget.fire) {
  //    assert(finished || dut.io.deq.target.bits === expectedOutputPayload, "Output token does not match expected value")
  //    expectedOutputPayload := expectedOutputPayload + 1.U //clockRatio.denominator.U

  //    finished := finished || expectedOutputPayload === (numNonEmptyTokens - 1).U
  //  }
  //}

  //io.finished := finished

  //dut.io.traceLen := DontCare
  //dut.io.traceLen := DontCare
  //// TODO: FIXME
  //dut.io.targetReset.valid := reset.toBool()
  //dut.io.targetReset.bits := reset.toBool()
  //dut.io.trace.ready.ready := DontCare
  //dut.io.trace.valid.ready := DontCare
  //dut.io.trace.bits.ready := DontCare
  //dut.io.traceLen := DontCare
}
