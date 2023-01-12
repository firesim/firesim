// See LICENSE for license details.

package midas.core

import freechips.rocketchip.tilelink.LFSR64 // Better than chisel's

import chisel3._
import chisel3.util._

// Describes an input channel / input port pair for an LI-BDN unittest
// name: a descriptive channel name
// reference: a hardware handle to the input on the reference RTL
// modelChannel: a hardware handle to the input channel on the model
// tokenGenFunc: a option carrying a function that when excuted,
//               generates hardware to produce a new input value each cycle
case class IChannelDesc[T <: Data](
  name: String,
  reference: T,
  modelChannel: DecoupledIO[T],
  tokenGenFunc: Option[() => T] = None) {

  private def tokenSequenceGenerator(typ: Data): Data =
    Cat(Seq.fill((typ.getWidth + 63)/64)(LFSR64()))(typ.getWidth - 1, 0).asTypeOf(typ)

  // Generate the testing hardware for a single input channel of a model
  def genEnvironment(testLength: Int): Unit = {
    val inputGen = tokenGenFunc.getOrElse(() => tokenSequenceGenerator(reference.cloneType))()

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

// Describes an output channel / output port pair for an LI-BDN unittest
// name: a descriptive channel name
// reference: a hardware handle to the output on the reference RTL
// modelChannel: a hardware handle to the output channel on the model
// comparisonFunc: a function that elaborates hardware to compare
//                 an output token Decoupled[Data] to the correct reference output [Data]
case class OChannelDesc[T <: Data](
  name: String,
  reference: T,
  modelChannel: DecoupledIO[T],
  comparisonFunc: (Data, DecoupledIO[Data]) => Bool = (a, b) => !b.fire || a.asUInt === b.bits.asUInt) {

  // Generate the testing hardware for a single output channel of a model
  def genEnvironment(testLength: Int): Bool = {
    val refOutputs = Module(new Queue(reference.cloneType, testLength, flow = true))
    val refIdx   = RegInit(0.U(log2Ceil(testLength + 1).W))
    val modelIdx = RegInit(0.U(log2Ceil(testLength + 1).W))

    val hValidPrev = RegNext(modelChannel.valid, false.B)
    val hReadyPrev = RegNext(modelChannel.ready)
    val hFirePrev = hValidPrev && hReadyPrev

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

object TokenComparisonFunctions{
  // Ignores the first N output tokens when verifying a token output trace
  def ignoreNTokens(numTokens: Int)(ref: Data, ch: DecoupledIO[Data]): Bool = {
    val count = RegInit(0.U(log2Ceil(numTokens + 1).W))
    val ignoreToken = count < numTokens.U
    when (ch.fire && ignoreToken) { count := count + 1.U }
    !ch.fire || ignoreToken || ref.asUInt === ch.bits.asUInt
  }
}

object DirectedLIBDNTestHelper{
  def apply(
      inputChannelMapping:  Seq[IChannelDesc[_]],
      outputChannelMapping: Seq[OChannelDesc[_]],
      testLength: Int = 4096): Bool = {
    inputChannelMapping.foreach(_.genEnvironment(testLength))
    val finished = outputChannelMapping.map(_.genEnvironment(testLength)).foldLeft(true.B)(_ && _)
    finished
  }
}
