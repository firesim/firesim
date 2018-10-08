// See LICENSE for license details.

package midas
package widgets

import scala.collection.immutable.ListMap

import Chisel._
import freechips.rocketchip.config.Parameters

import midas.core.SimUtils._

class ChannelRecord(channels: Seq[ChTuple]) extends Record {
  val elements = ListMap((channels map { case (elm, name) =>
    name -> Decoupled(elm.cloneType)
  }):_*)
  def cloneType = new ChannelRecord(channels).asInstanceOf[this.type]
}

class PeekPokeIOWidgetIO(inputs: Seq[ChTuple], outputs: Seq[ChTuple])
    (implicit p: Parameters) extends WidgetIO()(p) {
  // Channel width == width of simulation MMIO bus
  // Place for a heterogenous seq
  val ins  = new ChannelRecord(inputs)
  val outs = Flipped(new ChannelRecord(outputs))

  val step = Flipped(Decoupled(UInt(ctrl.nastiXDataBits.W)))
  val idle = Bool(OUTPUT)
}

// The interface to this widget is temporary, and matches the Vec of channels
// the sim wrapper produces. Ultimately, the wrapper should have more coarsely
// tokenized IOs.
class PeekPokeIOWidget(inputs: Seq[ChTuple], outputs: Seq[ChTuple])
    (implicit p: Parameters) extends Widget()(p) {
  val io = IO(new PeekPokeIOWidgetIO(inputs, outputs))

  // i = input, o = output tokens (as seen from the target)
  val iTokensAvailable = RegInit(0.U(ctrlWidth.W))
  val oTokensPending = RegInit(1.U(ctrlWidth.W))

  // needs back pressure from reset queues
  val fromHostReady = io.ins.elements.foldLeft(true.B)(_ && _._2.ready)
  val toHostValid = io.outs.elements.foldLeft(true.B)(_ && _._2.valid)

  io.idle := iTokensAvailable === 0.U && oTokensPending === 0.U

  def bindInputs(name: String, channel: DecoupledIO[ChLeafType]): Seq[Int] = {
    val reg = Reg(channel.bits)
    reg suggestName ("target_" + name)
    channel.bits := reg
    channel.valid := iTokensAvailable =/= UInt(0) && fromHostReady
    attachWide(reg, name)
  }

  def bindOutputs(name: String, channel: DecoupledIO[ChLeafType]): Seq[Int] = {
    channel.ready := oTokensPending =/= UInt(0) && toHostValid
    val reg = RegEnable(channel.bits, channel.fire)
    reg suggestName ("target_" + name)
    attachWide(reg, name)
  }

  val inputAddrs = io.ins.elements.map(elm => bindInputs(elm._1, elm._2))
  val outputAddrs = io.outs.elements.map(elm => bindOutputs(elm._1, elm._2))

  when (iTokensAvailable =/= UInt(0) && fromHostReady) {
    iTokensAvailable := iTokensAvailable - UInt(1)
  }

  when (oTokensPending =/= UInt(0) && toHostValid) {
    oTokensPending := oTokensPending - UInt(1)
  }

  when (io.step.fire) {
    iTokensAvailable := io.step.bits
    oTokensPending := io.step.bits
  }
  // For now do now, do not allow the block to be stepped further, unless
  // it has gone idle
  io.step.ready := io.idle

  // Target reset connection
  // Hack: insert high to resetQueue as initial tokens
  genCRFile()

  override def genHeader(base: BigInt, sb: StringBuilder): Unit = {
    import CppGenerationUtils._

    def genOffsets(signals: Seq[String]): Unit = (signals.zipWithIndex) foreach {
      case (name, idx) => sb.append(genConstStatic(name, UInt32(idx)))}

    sb.append(genComment("Pokeable target inputs"))
    sb.append(genMacro("POKE_SIZE", UInt64(inputs.size)))
    genOffsets(inputs.unzip._2)
    sb.append(genArray("INPUT_ADDRS", inputAddrs.map(off => UInt32(base + off.head)).toSeq))
    sb.append(genArray("INPUT_NAMES", inputs.unzip._2 map CStrLit))
    sb.append(genArray("INPUT_CHUNKS", inputAddrs.map(addrSeq => UInt32(addrSeq.size)).toSeq))

    sb.append(genComment("Peekable target outputs"))
    sb.append(genMacro("PEEK_SIZE", UInt64(outputs.size)))
    genOffsets(outputs.unzip._2)
    sb.append(genArray("OUTPUT_ADDRS", outputAddrs.map(off => UInt32(base + off.head)).toSeq))
    sb.append(genArray("OUTPUT_NAMES", outputs.unzip._2 map CStrLit))
    sb.append(genArray("OUTPUT_CHUNKS", outputAddrs.map(addrSeq => UInt32(addrSeq.size)).toSeq))
  }
}
