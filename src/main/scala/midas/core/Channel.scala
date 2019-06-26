// See LICENSE for license details.

package midas
package core

import freechips.rocketchip.config.Parameters
import freechips.rocketchip.unittest._
import freechips.rocketchip.util.{DecoupledHelper}
import freechips.rocketchip.tilelink.LFSR64 // Better than chisel's

import chisel3._
import chisel3.util._
import chisel3.experimental.{dontTouch, chiselName, MultiIOModule}

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
    latency: Int,
    clockRatio: IsRationalClockRatio = UnityClockRatio
  )(implicit p: Parameters) extends Module {

  require(clockRatio.isUnity)
  require(latency == 0 || latency == 1)

  val io = IO(new WireChannelIO(gen))
  val tokens = Module(new Queue(gen, p(ChannelLen)))
  tokens.io.enq <> io.in
  io.out <> tokens.io.deq

  if (latency == 1) {
    val initializing = RegNext(reset.toBool)
    when(initializing) {
      tokens.io.enq.valid := true.B
      io.in.ready := false.B
    }
  }

  if (p(EnableSnapshot)) {
    io.trace <> TraceQueue(tokens.io.deq, io.traceLen)
  } else {
    io.trace := DontCare
    io.trace.valid := false.B
  }
}

case class IChannelDesc(
  name: String,
  reference: Data,
  modelChannel: DecoupledIO[Data]) {

  private def tokenSequenceGenerator(typ: Data): Data =
    Cat(Seq.fill((typ.getWidth + 63)/64)(LFSR64()))(typ.getWidth - 1, 0).asTypeOf(typ)

  // Generate the testing hardware for a single input channel of a model
  @chiselName
  def genEnvironment(testLength: Int): Unit = {
    val inputGen = tokenSequenceGenerator(reference.cloneType)

    // Drive a new input to the reference on every cycle
    reference := inputGen

    // Drive tokenzied inputs to the model
    val inputTokenQueue = Module(new Queue(reference.cloneType, testLength, flow = true))
    inputTokenQueue.io.enq.bits := reference
    inputTokenQueue.io.enq.valid := true.B

    // This provides an irrevocable input token stream
    val stickyTokenValid = Reg(Bool())
    modelChannel <> inputTokenQueue.io.deq
    modelChannel.valid := stickyTokenValid && inputTokenQueue.io.deq.valid
    inputTokenQueue.io.deq.ready := stickyTokenValid && modelChannel.ready

    when (modelChannel.fire || ~stickyTokenValid) {
      stickyTokenValid := LFSR64()(1)
    }
  }
}

case class OChannelDesc(
  name: String,
  reference: Data,
  modelChannel: DecoupledIO[Data],
  comparisonFunc: (Data, DecoupledIO[Data]) => Bool = (a, b) => !b.fire || a.asUInt === b.bits.asUInt) {

  // Generate the testing hardware for a single output channel of a model
  @chiselName
  def genEnvironment(testLength: Int): Bool = {
    val refOutputs = Module(new Queue(reference.cloneType, testLength, flow = true))
    val refIdx   = RegInit(0.U(log2Ceil(testLength + 1).W))
    val modelIdx = RegInit(0.U(log2Ceil(testLength + 1).W))

    val hValidPrev = RegNext(modelChannel.valid, false.B)
    val hReadyPrev = RegNext(modelChannel.ready)
    val hFirePrev = hValidPrev && hReadyPrev
    //suggestedName match {
    //  case Some(name) => name + ": "
    //  case None => ""
    //}

    // Collect outputs from the reference RTL
    refOutputs.io.enq.valid := true.B
    refOutputs.io.enq.bits := reference

    assert(comparisonFunc(refOutputs.io.deq.bits, modelChannel),
      s"${name} Channel: Output token traces did not match")
    assert(!hValidPrev || hFirePrev || modelChannel.valid,
      s"${name} Channel: hValid de-asserted without handshake, violating output token irrevocability")

    val modelChannelDone = modelIdx === testLength.U
    when (modelChannel.fire) { modelIdx := modelIdx + 1.U }
    refOutputs.io.deq.ready := modelChannel.fire

    // Fuzz backpressure on the token channel
    modelChannel.ready := LFSR64()(1) & !modelChannelDone

    // Return the done signal
    modelChannelDone
  }
}

object DirectedLIBDNTestHelper{
  def ignoreNTokens(numTokens: Int)(ref: Data, ch: DecoupledIO[Data]): Bool = {
    val count = RegInit(0.U(32.W))
    when (ch.fire) { count := count + 1.U }
    !ch.fire || count < numTokens.U || ref.asUInt === ch.bits.asUInt
  }

  @chiselName
  def apply(
      inputChannelMapping:  Seq[IChannelDesc],
      outputChannelMapping: Seq[OChannelDesc],
      testLength: Int = 4096): Bool = {
    inputChannelMapping.foreach(_.genEnvironment(testLength))
    val finished = outputChannelMapping.map(_.genEnvironment(testLength)).foldLeft(true.B)(_ && _)
    finished
  }
}

class WireChannelUnitTest(
    latency: Int = 0,
    numTokens: Int = 4096,
    timeout: Int = 50000
  )(implicit p: Parameters) extends UnitTest(timeout) {

  override val testName = "WireChannel Unit Test"
  val payloadWidth = 8
  val dut = Module(new WireChannel(UInt(payloadWidth.W), latency, UnityClockRatio))
  val referenceInput  = Wire(UInt(payloadWidth.W))
  val referenceOutput = ShiftRegister(referenceInput, latency)

  val inputChannelMapping = Seq(IChannelDesc("in", referenceInput, dut.io.in))
  val outputChannelMapping = Seq(OChannelDesc("out", referenceOutput, dut.io.out, DirectedLIBDNTestHelper.ignoreNTokens(1)))

  io.finished := DirectedLIBDNTestHelper(inputChannelMapping, outputChannelMapping, numTokens)

  dut.io.traceLen := DontCare
  dut.io.trace.ready := DontCare
}

// A bidirectional token channel wrapping a target-decoupled (ready-valid) interface
// Structurally, this keeps the target bundle intact however it should really be thought of as:
// two *independent* token channels
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

  // Returns two directioned objects driven by this SimReadyValidIO hw instance
  def bifurcate(): (DecoupledIO[ValidIO[T]], DecoupledIO[Bool]) = {
    // Can't use bidirectional wires, so we use a dummy module (akin to the identity module)
    class BifurcationModule[T <: Data](gen: T) extends MultiIOModule {
      val fwd = IO(Decoupled(Valid(gen)))
      val rev = IO(Flipped(DecoupledIO(Bool())))
      val coupled = IO(Flipped(cloneType))
      // Forward channel
      fwd.bits.bits  := coupled.target.bits
      fwd.bits.valid := coupled.target.valid
      fwd.valid      := coupled.fwd.hValid
      coupled.fwd.hReady := fwd.ready
      // Reverse channel
      rev.ready := coupled.rev.hReady
      coupled.target.ready := rev.bits
      coupled.rev.hValid   := rev.valid
    }
    val bifurcator = Module(new BifurcationModule(gen))
    bifurcator.coupled <> this
    (bifurcator.fwd, bifurcator.rev)
  }

  // Returns two directioned objects which will drive this SimReadyValidIO hw instance
  def combine(): (DecoupledIO[ValidIO[T]], DecoupledIO[Bool]) = {
    // Can't use bidirectional wires, so we use a dummy module (akin to the identity module)
    class CombiningModule[T <: Data](gen: T) extends MultiIOModule {
      val fwd = IO(Flipped(DecoupledIO(Valid(gen))))
      val rev = IO((Decoupled(Bool())))
      val coupled = IO(cloneType)
      // Forward channel
      coupled.target.bits := fwd.bits.bits
      coupled.target.valid := fwd.bits.valid
      coupled.fwd.hValid := fwd.valid
      fwd.ready := coupled.fwd.hReady
      // Reverse channel
      coupled.rev.hReady := rev.ready
      rev.bits := coupled.target.ready
      rev.valid := coupled.rev.hValid
    }
    val combiner = Module(new CombiningModule(gen))
    this <> combiner.coupled
    (combiner.fwd, combiner.rev)
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

  // Use an additional cycle to populate initial tokens
  val initializing = RegNext(reset.toBool)

  // Consume the enq-fwd token if:
  //  1) the target queue isn't empty -> enq and deq sides can decouple in target-time 
  //  2) the target queue is full but token queue is empty -> enq & deq sides have lost decoupling
  //     and we know this payload will not be enqueued in target-land
  val doFwdEnq = target.io.enq.ready || !tokens.io.deq.valid

  // NB: This creates an extraenous dep between enq.fwd and reset
  val enqFwdHelper = DecoupledHelper(
    io.targetReset.valid,
    tokens.io.enq.ready,
    io.enq.fwd.hValid,
    doFwdEnq)

  target.reset := reset.toBool || initializing || enqFwdHelper.fire && io.targetReset.bits
  // ENQ DOMAIN LOGIC
  // Enq-fwd token control
  target.io.enq.valid := enqFwdHelper.fire && io.enq.target.valid
  target.io.enq.bits  := io.enq.target.bits
  tokens.io.enq.valid := initializing || enqFwdHelper.fire(tokens.io.enq.ready)
  tokens.io.enq.bits  := !initializing && io.enq.target.valid && target.io.enq.ready && !io.targetReset.bits

  io.enq.fwd.hReady := !initializing && enqFwdHelper.fire(io.enq.fwd.hValid)
  io.targetReset.ready := !initializing && enqFwdHelper.fire(io.targetReset.valid)

  // Enq-rev token control
  // Assumptions: model a queue with flow = false
  // Simplifications: Only let rev-tokens slip behind fwd-tokens for now.

  // Capture ready tokens that would be dropped
  val readyTokens = Module(new Queue(Bool(), n))
  readyTokens.io.enq.valid := enqFwdHelper.fire && !io.enq.rev.hReady
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
  io.deq.rev.hReady := !initializing && !deqRevSlip
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
    numTokens: Int = 4096,
    timeout: Int = 50000
  )(implicit p: Parameters) extends UnitTest(timeout) {
  override val testName = "WireChannel ClockRatio: ${clockRatio.numerator}/${clockRatio.denominator}"

  val payloadType = UInt(8.W)

  val dut = Module(new ReadyValidChannel(payloadType))
  val reference = Module(new Queue(payloadType, 2))

  val (deqFwd, deqRev) = dut.io.deq.bifurcate()
  val (enqFwd, enqRev) = dut.io.enq.combine()

  val refDeqFwd = Wire(Valid(payloadType))
  refDeqFwd.bits := reference.io.deq.bits
  refDeqFwd.valid := reference.io.deq.valid
  val refEnqFwd = Wire(Valid(payloadType))
  reference.io.enq.bits := refEnqFwd.bits
  reference.io.enq.valid := refEnqFwd.valid

  val inputChannelMapping = Seq(IChannelDesc("enqFwd", refEnqFwd, enqFwd),
                                IChannelDesc("deqRev", reference.io.deq.ready, deqRev),
                                IChannelDesc("reset" , reference.reset.toBool, dut.io.targetReset))

  val outputChannelMapping = Seq(OChannelDesc("deqFwd", refDeqFwd, deqFwd),
                                 OChannelDesc("enqRev", reference.io.enq.ready, enqRev))

  io.finished := DirectedLIBDNTestHelper(inputChannelMapping, outputChannelMapping, numTokens)

  dut.io.traceLen := DontCare
  dut.io.trace.ready := DontCare
  //// Count host-level handshakes on tokens
  //
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

  //val outputFwdHReadyFuzz = LFSR64()(0) // Payload & valid token ready
  //val outputRevHValidFuzz = LFSR64()(0) // Ready token valid
  //val outputRevTReadyFuzz = LFSR64()(0) // Ready token value
  //val inputFwdHValidFuzz  = LFSR64()(0) // Payload & valid token valid
  //val inputFwdTValidFuzz  = LFSR64()(0) // Valid-token value
  //val inputRevHReadyFuzz  = LFSR64()(0) // Ready-token ready

  //val finished = RegInit(false.B)

  //dut.io.deq.fwdIrrevocabilityAssertions(Some("deq."))
  //dut.io.enq.fwdIrrevocabilityAssertions(Some("enq."))
  //dut.io.deq.revIrrevocabilityAssertions(Some("deq."))
  //dut.io.enq.revIrrevocabilityAssertions(Some("enq."))

  //io.finished := finished

  //// TODO: FIXME
  //dut.io.targetReset.valid := reset.toBool()
  //dut.io.targetReset.bits := reset.toBool()


  //dut.io.traceLen := DontCare
  //dut.io.traceLen := DontCare
  //dut.io.trace.ready.ready := DontCare
  //dut.io.trace.valid.ready := DontCare
  //dut.io.trace.bits.ready := DontCare
  //dut.io.traceLen := DontCare
}
