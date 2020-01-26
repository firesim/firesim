// See LICENSE for license details.

package midas
package core

import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.unittest._
import freechips.rocketchip.util.{DecoupledHelper}
import freechips.rocketchip.tilelink.LFSR64 // Better than chisel's

import chisel3._
import chisel3.util._
import chisel3.experimental.{chiselName}

import strober.core.{TraceQueue, TraceMaxLen}
import midas.core.SimUtils.{ChLeafType}

// Generates stateful assertions on the ports of channels to check that token
// irrevocability constraints aren't be violated. Bridges that don not produce
// token streams irrevocably will introduce simulation non-determinism.
case object GenerateTokenIrrevocabilityAssertions extends Field[Boolean](false)

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

class PipeChannelIO[T <: ChLeafType](gen: T)(implicit p: Parameters) extends Bundle {
  val in    = Flipped(Decoupled(gen))
  val out   = Decoupled(gen)
  val trace = Decoupled(gen)
  val traceLen = Input(UInt(log2Up(p(TraceMaxLen)+1).W))
  override def cloneType = new PipeChannelIO(gen)(p).asInstanceOf[this.type]

}

class PipeChannel[T <: ChLeafType](
    val gen: T,
    latency: Int,
    clockRatio: IsRationalClockRatio = UnityClockRatio
  )(implicit p: Parameters) extends Module {

  require(clockRatio.isUnity)
  require(latency == 0 || latency == 1)

  val io = IO(new PipeChannelIO(gen))
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

  if (p(GenerateTokenIrrevocabilityAssertions)) AssertTokenIrrevocable(io.in, None)

  if (p(EnableSnapshot)) {
    io.trace <> TraceQueue(tokens.io.deq, io.traceLen)
  } else {
    io.trace := DontCare
    io.trace.valid := false.B
  }
}

// Generates stateful assertions to check that an ReadyValid interface is irrevocable
object AssertTokenIrrevocable {
  def apply(valid: Bool, bits: Data, ready: Bool, suggestedName: Option[String]): Unit = {
    val prefix = suggestedName match {
      case Some(str) => str + ": "
      case None => ""
    }
    val validPrev = RegNext(valid, false.B)
    val bitsPrev  = RegNext(bits)
    val firePrev  = RegNext(valid && ready)
    assert(!validPrev || firePrev || valid,
      s"${prefix}valid de-asserted without handshake, violating irrevocability")
    assert(!validPrev || firePrev || bitsPrev.asUInt === bits.asUInt,
      s"${prefix}bits changed without handshake, violating irrevocability")
  }

  def apply(rv: ReadyValidIO[_ <: Data], suggestedName: Option[String] = None): Unit =
    apply(rv.valid, rv.bits, rv.ready, suggestedName)
}

class PipeChannelUnitTest(
    latency: Int = 0,
    numTokens: Int = 4096,
    timeout: Int = 50000
  )(implicit p: Parameters) extends UnitTest(timeout) {

  override val testName = "PipeChannel Unit Test"
  val payloadWidth = 8
  val dut = Module(new PipeChannel(UInt(payloadWidth.W), latency, UnityClockRatio))
  val referenceInput  = Wire(UInt(payloadWidth.W))
  val referenceOutput = ShiftRegister(referenceInput, latency)

  val inputChannelMapping = Seq(IChannelDesc("in", referenceInput, dut.io.in))
  val outputChannelMapping = Seq(OChannelDesc("out", referenceOutput, dut.io.out, TokenComparisonFunctions.ignoreNTokens(1)))

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

  def generateFwdIrrevocabilityAssertions(suggestedName: Option[String] = None): Unit =
    AssertTokenIrrevocable(fwd.hValid, Cat(target.valid, target.bits.asUInt), fwd.hReady, suggestedName)

  def generateRevIrrevocabilityAssertions(suggestedName: Option[String] = None): Unit =
    AssertTokenIrrevocable(rev.hValid, target.ready, rev.hReady, suggestedName)

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
  val enqFwdQ = Module(new Queue(ValidIO(gen), 2, flow = true))
  enqFwdQ.io.enq.bits.valid := io.enq.target.valid
  enqFwdQ.io.enq.bits.bits := io.enq.target.bits
  enqFwdQ.io.enq.valid := io.enq.fwd.hValid
  io.enq.fwd.hReady := enqFwdQ.io.enq.ready

  val deqRevQ = Module(new Queue(Bool(), 2, flow = true))
  deqRevQ.io.enq.bits  := io.deq.target.ready
  deqRevQ.io.enq.valid := io.deq.rev.hValid
  io.deq.rev.hReady    := deqRevQ.io.enq.ready

  val reference = Module(new Queue(gen, n))
  val deqFwdFired = RegInit(false.B)
  val enqRevFired = RegInit(false.B)

  val finishing = DecoupledHelper(
    io.targetReset.valid,
    enqFwdQ.io.deq.valid,
    deqRevQ.io.deq.valid,
    (enqRevFired || io.enq.rev.hReady),
    (deqFwdFired || io.deq.fwd.hReady))

  val targetFire = finishing.fire()
  val enqBitsLast = RegEnable(enqFwdQ.io.deq.bits.bits, targetFire)
  // enqRev
  io.enq.rev.hValid := !enqRevFired
  io.enq.target.ready := reference.io.enq.ready

  // deqFwd
  io.deq.fwd.hValid := !deqFwdFired
  io.deq.target.bits := reference.io.deq.bits
  io.deq.target.valid := reference.io.deq.valid

  io.targetReset.ready := finishing.fire(io.targetReset.valid)
  enqFwdQ.io.deq.ready := finishing.fire(enqFwdQ.io.deq.valid)
  deqRevQ.io.deq.ready := finishing.fire(deqRevQ.io.deq.valid)

  reference.reset := reset.toBool || targetFire && io.targetReset.bits
  reference.io.enq.valid := targetFire && enqFwdQ.io.deq.bits.valid
  reference.io.enq.bits  := Mux(targetFire, enqFwdQ.io.deq.bits.bits, enqBitsLast)
  reference.io.deq.ready := targetFire && deqRevQ.io.deq.bits

  deqFwdFired := Mux(targetFire, false.B, deqFwdFired || io.deq.fwd.hReady)
  enqRevFired := Mux(targetFire, false.B, enqRevFired || io.enq.rev.hReady)

  if (p(GenerateTokenIrrevocabilityAssertions)) {
    io.enq.generateFwdIrrevocabilityAssertions()
    io.deq.generateRevIrrevocabilityAssertions()
  }

  io.trace := DontCare
  io.trace.bits.valid  := false.B
  io.trace.valid.valid := false.B
  io.trace.ready.valid := false.B
}

@chiselName
class ReadyValidChannelUnitTest(
    numTokens: Int = 4096,
    queueDepth: Int = 2,
    timeout: Int = 50000
  )(implicit p: Parameters) extends UnitTest(timeout) {
  override val testName = "PipeChannel ClockRatio: ${clockRatio.numerator}/${clockRatio.denominator}"

  val payloadType = UInt(8.W)
  val resetLength = 4

  val dut = Module(new ReadyValidChannel(payloadType))
  val reference = Module(new Queue(payloadType, queueDepth))

  // Generates target-reset tokens
  def resetTokenGen(): Bool = {
    val resetCount = RegInit(0.U(log2Ceil(resetLength + 1).W))
    val outOfReset = resetCount === resetLength.U
    resetCount := Mux(outOfReset, resetCount, resetCount + 1.U)
    !outOfReset
  }

  // This will ensure that the bits field of deq matches even if target valid
  // is not asserted. To workaround random initialization of the queue's
  // mem, it neglects all target-invalid output tokens until all entries of
  // the mem has been written once.
  //
  // TODO: Consider initializing all memories to zero even in the unittests as
  // that will more closely the FPGA
  val enqCount = RegInit(0.U(log2Ceil(queueDepth + 1).W))
  val memFullyDefined = enqCount === queueDepth.U
  enqCount := Mux(!memFullyDefined && reference.io.enq.fire && !reference.reset.toBool, enqCount + 1.U, enqCount)

  // Track the target cycle at which all entries are known
  val memFullyDefinedCycle = RegInit(1.U(log2Ceil(2*timeout).W))
  memFullyDefinedCycle := Mux(!memFullyDefined, memFullyDefinedCycle + 1.U, memFullyDefinedCycle)

  def strictPayloadCheck(ref: Data, ch: DecoupledIO[Data]): Bool = {
    // hack: fix the types
    val refTyped = ref.asTypeOf(refDeqFwd)
    val modelTyped = ref.asTypeOf(refDeqFwd)

    val deqCount = RegInit(0.U(log2Ceil(numTokens + 1).W))
    when (ch.fire) { deqCount := deqCount + 1.U }

    // Neglect a comparison if: 1) still under reset 2) mem contents still undefined
    val exempt = deqCount < resetLength.U ||
      !refTyped.valid && !modelTyped.valid && (deqCount < memFullyDefinedCycle)
    val matchExact = ref.asUInt === ch.bits.asUInt

    !ch.fire || exempt || matchExact
  }

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
                                IChannelDesc("reset" , reference.reset, dut.io.targetReset, Some(resetTokenGen)))

  val outputChannelMapping = Seq(OChannelDesc("deqFwd", refDeqFwd, deqFwd, strictPayloadCheck),
                                 OChannelDesc("enqRev", reference.io.enq.ready, enqRev, TokenComparisonFunctions.ignoreNTokens(resetLength)))

  io.finished := DirectedLIBDNTestHelper(inputChannelMapping, outputChannelMapping, numTokens)

  dut.io.traceLen := DontCare
  dut.io.traceLen := DontCare
  dut.io.trace.ready.ready := DontCare
  dut.io.trace.valid.ready := DontCare
  dut.io.trace.bits.ready := DontCare
  dut.io.traceLen := DontCare
}
