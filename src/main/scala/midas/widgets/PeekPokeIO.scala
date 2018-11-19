// See LICENSE for license details.

package midas
package widgets

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters

trait HasChannels {
  // A higher order funciton that takes a hardware elaborator for each channel.
  // Based on the number of chunks in each signal binds the appropriate
  // number of programmable registers to them
  def bindChannels(bindSignal: (String, Int) => Int)(
      signals: Seq[(String, Int)], offset: Int): Seq[Int] = signals match {
    case Nil => Nil
    case (name: String, width: Int) :: sigs => {
      val address = width match {
        case 1 =>  bindSignal(name, offset)
        case _ =>
          // Need to append an offset to the name for each chunk
          (0 until width).toSeq.map(chunk => bindSignal(s"${name}_$chunk", offset + chunk)).head
      }
      // Bind the next signal; moving further down
      address +: bindChannels(bindSignal)(sigs, offset + width)
    }
  }
}

class PeekPokeIOWidgetIO(val inNum: Int, val outNum: Int)(implicit val p: Parameters)
    extends WidgetIO()(p) {
  // Channel width == width of simulation MMIO bus
  val ins  = Vec(inNum, Decoupled(UInt(ctrl.nastiXDataBits.W)))
  val outs = Flipped(Vec(outNum, Decoupled(UInt(ctrl.nastiXDataBits.W))))

  val step = Flipped(Decoupled(UInt(ctrl.nastiXDataBits.W)))
  val idle = Output(Bool())
  val tReset = Decoupled(Bool())
}

// The interface to this widget is temporary, and matches the Vec of channels
// the sim wrapper produces. Ultimately, the wrapper should have more coarsely
// tokenized IOs.
class PeekPokeIOWidget(inputs: Seq[(String, Int)], outputs: Seq[(String, Int)])
    (implicit p: Parameters) extends Widget()(p) with HasChannels {
  val numInputChannels = (inputs.unzip._2 foldLeft 0)(_ + _)
  val numOutputChannels = (outputs.unzip._2 foldLeft 0)(_ + _)
  val io = IO(new PeekPokeIOWidgetIO(numInputChannels, numOutputChannels))

  // i = input, o = output tokens (as seen from the target)
  val iTokensAvailable = RegInit(0.U(io.ctrl.nastiXDataBits.W))
  val oTokensPending = RegInit(1.U(io.ctrl.nastiXDataBits.W))
  val tCycleName = "tCycle"
  val tCycle = genWideRORegInit(0.U(64.W), tCycleName)
  val hCycleName = "hCycle"
  val hCycle = genWideRORegInit(0.U(64.W), hCycleName)

  // needs back pressure from reset queues
  val fromHostReady = io.ins.foldLeft(io.tReset.ready)(_ && _.ready)
  val toHostValid = io.outs.foldLeft(io.tReset.ready)(_ && _.valid)

  io.idle := iTokensAvailable === 0.U && oTokensPending === 0.U

  def bindInputs = bindChannels((name, offset) => {
    val channel = io.ins(offset)
    val reg = Reg(channel.bits.cloneType)
    reg suggestName ("target_" + name)
    channel.bits := reg
    channel.valid := iTokensAvailable =/= 0.U && fromHostReady
    attach(reg, name)
  }) _

  def bindOutputs = bindChannels((name, offset) => {
    val channel = io.outs(offset)
    val reg = RegEnable(channel.bits, channel.fire)
    reg suggestName ("target_" + name)
    channel.ready := oTokensPending =/= 0.U && toHostValid
    attach(reg, name)
  }) _

  val inputAddrs = bindInputs(inputs, 0)
  val outputAddrs = bindOutputs(outputs, 0)

  when (iTokensAvailable =/= 0.U && fromHostReady) {
    iTokensAvailable := iTokensAvailable - 1.U
  }

  when (oTokensPending =/= 0.U && toHostValid) {
    oTokensPending := oTokensPending - 1.U
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

  // Target reset connection
  // Hack: insert high to resetQueue as initial tokens
  val resetNext = RegNext(reset)
  io.tReset.bits := resetNext.toBool || io.ins(0).bits(0)
  io.tReset.valid := resetNext.toBool || io.ins(0).valid

  genCRFile()

  override def genHeader(base: BigInt, sb: StringBuilder): Unit = {
    import CppGenerationUtils._

    val name = getWName.toUpperCase
    def genOffsets(signals: Seq[String]): Unit = (signals.zipWithIndex) foreach {
      case (name, idx) => sb.append(genConstStatic(name, UInt32(idx)))}

    super.genHeader(base, sb)
    sb.append(genComment("Pokeable target inputs"))
    sb.append(genMacro("POKE_SIZE", UInt64(inputs.size)))
    genOffsets(inputs.unzip._1)
    sb.append(genArray("INPUT_ADDRS", inputAddrs map (off => UInt32(base + off))))
    sb.append(genArray("INPUT_NAMES", inputs.unzip._1 map CStrLit))
    sb.append(genArray("INPUT_CHUNKS", inputs.unzip._2 map (UInt32(_))))

    sb.append(genComment("Peekable target outputs"))
    sb.append(genMacro("PEEK_SIZE", UInt64(outputs.size)))
    genOffsets(outputs.unzip._1)
    sb.append(genArray("OUTPUT_ADDRS", outputAddrs map (off => UInt32(base + off))))
    sb.append(genArray("OUTPUT_NAMES", outputs.unzip._1 map CStrLit))
    sb.append(genArray("OUTPUT_CHUNKS", outputs.unzip._2 map (UInt32(_))))
  }
}
