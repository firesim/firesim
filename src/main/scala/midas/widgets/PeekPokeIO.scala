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
  val oTokensPending = RegInit(0.U(ctrlWidth.W))
  val tCycleName = "tCycle"
  val tCycle = genWideRORegInit(0.U(64.W), tCycleName)
  val hCycleName = "hCycle"
  val hCycle = genWideRORegInit(0.U(64.W), hCycleName)

  // needs back pressure from reset queues
  val fromHostReady = io.ins.elements.foldLeft(true.B)(_ && _._2.ready)
  val toHostValid = io.outs.elements.foldLeft(true.B)(_ && _._2.valid)

  io.idle := iTokensAvailable === 0.U && oTokensPending === 0.U

  def genWideReg(name: String, field: ChLeafType): Seq[UInt] = Seq.tabulate(
      (field.getWidth + ctrlWidth - 1) / ctrlWidth)({ i => 
    val chunkWidth = math.min(ctrlWidth, field.getWidth - (i * ctrlWidth))
    Reg(UInt(chunkWidth.W)).suggestName(s"target_${name}_{i}")
  })

  def bindInputs(name: String, channel: DecoupledIO[ChLeafType]): Seq[Int] = {
    val reg = genWideReg(name, channel.bits)
    channel.bits := Cat(reg.reverse)
    channel.valid := iTokensAvailable =/= UInt(0) && fromHostReady
    reg.zipWithIndex.map({ case (chunk, idx) => attach(chunk,  s"${name}_${idx}", ReadWrite) })
  }

  def bindOutputs(name: String, channel: DecoupledIO[ChLeafType]): Seq[Int] = {
    val reg = genWideReg(name, channel.bits)
    channel.ready := oTokensPending =/= UInt(0) && toHostValid
    when (channel.fire) {
      reg.zipWithIndex.foreach({ case (reg, i) =>
        val msb = math.min(ctrlWidth * (i + 1) - 1, channel.bits.getWidth - 1)
        reg := channel.bits(msb, ctrlWidth * i)
      })
    }
    reg.zipWithIndex.map({ case (chunk, idx) => attach(chunk,  s"${name}_${idx}", ReadOnly) })
  }

  val inputAddrs = io.ins.elements.map(elm => bindInputs(elm._1, elm._2))
  val outputAddrs = io.outs.elements.map(elm => bindOutputs(elm._1, elm._2))

  when (iTokensAvailable =/= UInt(0) && fromHostReady) {
    iTokensAvailable := iTokensAvailable - UInt(1)
  }

  when (oTokensPending =/= UInt(0) && toHostValid) {
    oTokensPending := oTokensPending - UInt(1)
    tCycle := tCycle + 1.U
  }
  hCycle := hCycle + 1.U

  when (io.step.fire) {
    iTokensAvailable := io.step.bits
    oTokensPending := io.step.bits
  }
  // For now do now, do not allow the block to be stepped further, unless
  // it has gone idle
  io.step.ready := io.idle

  genCRFile()

  override def genHeader(base: BigInt, sb: StringBuilder): Unit = {
    import CppGenerationUtils._

    val name = getWName.toUpperCase
    def genOffsets(signals: Seq[String]): Unit = (signals.zipWithIndex) foreach {
      case (name, idx) => sb.append(genConstStatic(name, UInt32(idx)))}

    super.genHeader(base, sb)
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
