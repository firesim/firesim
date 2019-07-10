// See LICENSE for license details.

package midas
package widgets

import scala.collection.immutable.ListMap
import scala.collection.mutable

import chisel3._
import chisel3.util._
import chisel3.experimental.chiselName
import firrtl.annotations.{SingleTargetAnnotation} // Deprecated
import firrtl.annotations.{ReferenceTarget, ModuleTarget, AnnotationException}
import freechips.rocketchip.config.Parameters

import midas.core.SimUtils._
import midas.core.{SimReadyValid, SimReadyValidIO}
import midas.passes.fame.{FAMEChannelConnectionAnnotation, WireChannel}

class PeekPokeIOWidgetIO(private val peekPokeEndpointIO: PeekPokeEndpointIO)(implicit val p: Parameters)
    extends EndpointWidgetIO()(p) {
  // Channel width == width of simulation MMIO bus
  val hPort = peekPokeEndpointIO.cloneType

  val step = Flipped(Decoupled(UInt(ctrl.nastiXDataBits.W)))
  val idle = Output(Bool())
}
// The interface to this widget is temporary, and matches the Vec of channels
// the sim wrapper produces. Ultimately, the wrapper should have more coarsely
// tokenized IOs.
// Maximum channel decoupling puts a bound on the number of cycles the fastest
// channel can advance ahead of the slowest channel
class PeekPokeIOWidget(
    peekPokeIO: PeekPokeEndpointIO,
    maxChannelDecoupling: Int = 2) (implicit p: Parameters) extends EndpointWidget()(p) {
  val io = IO(new PeekPokeIOWidgetIO(peekPokeIO))
  // TODO: Remove me
  io.tReset.ready := true.B

  require(maxChannelDecoupling > 1, "A smaller channel decoupling could FMR")
  // Tracks the number of tokens the slowest channel has to produce or consume
  // before we reach the desired target cycle
  val cycleHorizon = RegInit(0.U(ctrlWidth.W))
  val tCycleName = "tCycle"
  val tCycle = genWideRORegInit(0.U(64.W), tCycleName)
  val tCycleAdvancing = Wire(Bool())

  val hCycleName = "hCycle"
  val hCycle = genWideRORegInit(0.U(64.W), hCycleName)

  // needs back pressure from reset queues
  io.idle := cycleHorizon === 0.U

  def genWideReg(name: String, field: ChLeafType): Seq[UInt] = Seq.tabulate(
      (field.getWidth + ctrlWidth - 1) / ctrlWidth)({ i =>
    val chunkWidth = math.min(ctrlWidth, field.getWidth - (i * ctrlWidth))
    Reg(UInt(chunkWidth.W)).suggestName(s"target_${name}_{i}")
  })

  // Asserted by a channel when it is advancing or has advanced ahead of tCycle
  val channelDecouplingFlags = mutable.ArrayBuffer[Bool]()
  val channelPokes           = mutable.ArrayBuffer[(Seq[Int], Bool)]()

  def bindInputs(name: String, channel: DecoupledIO[ChLeafType]): Seq[Int] = {
    val reg = genWideReg(name, channel.bits)
    // Track local-channel decoupling
    val cyclesAhead = SatUpDownCounter(maxChannelDecoupling)
    val isAhead = !cyclesAhead.empty || channel.fire
    cyclesAhead.inc := channel.fire
    cyclesAhead.dec := tCycleAdvancing

    // If a channel is being poked, allow it to enqueue it's output token
    // This lets us peek outputs that depend combinationally on inputs we poke
    // Asserted when a memory-mapped register associated with channel is being written to
    val poke = Wire(Bool())
    // Handle the fields > 32 bits
    val wordsReceived = RegInit(0.U(log2Ceil(reg.size + 1).W))
    val advanceViaPoke = wordsReceived === reg.size.U

    when (poke) {
      wordsReceived := wordsReceived + 1.U
    }.elsewhen(channel.fire) {
      wordsReceived := 0.U
    }

    channel.bits := Cat(reg.reverse).asTypeOf(channel.bits)
    channel.valid := cyclesAhead.value < cycleHorizon || advanceViaPoke

    val regAddrs = reg.zipWithIndex.map({ case (chunk, idx) => attach(chunk,  s"${name}_${idx}", ReadWrite) })
    channelDecouplingFlags += isAhead
    channelPokes += regAddrs -> poke
    regAddrs
  }

  def bindOutputs(name: String, channel: DecoupledIO[ChLeafType]): Seq[Int] = {
    val reg = genWideReg(name, channel.bits)
    // Track local-channel decoupling
    val cyclesAhead = SatUpDownCounter(maxChannelDecoupling)
    val isAhead = !cyclesAhead.empty || channel.fire
    cyclesAhead.inc := channel.fire
    cyclesAhead.dec := tCycleAdvancing

    // Let token sinks accept one additional token that sources can produce (if
    // they aren't poked) This, to allow, us to peek outputs that depend
    // combinationally on inputs
    channel.ready := cyclesAhead.value < (cycleHorizon + 1.U)
    when (channel.fire) {
      reg.zipWithIndex.foreach({ case (reg, i) =>
        val msb = math.min(ctrlWidth * (i + 1) - 1, channel.bits.getWidth - 1)
        reg := channel.bits(msb, ctrlWidth * i)
      })
    }

    channelDecouplingFlags += isAhead
    reg.zipWithIndex.map({ case (chunk, idx) => attach(chunk,  s"${name}_${idx}", ReadOnly) })
  }

  val inputAddrs = io.hPort.ins.map(elm => bindInputs(elm._1, elm._2))
  val outputAddrs = io.hPort.outs.map(elm => bindOutputs(elm._1, elm._2))

  tCycleAdvancing := channelDecouplingFlags.reduce(_ && _)
  // tCycleAdvancing can be asserted if all inputs have been poked; but only increment
  // tCycle if we've been asked to step (cycleHorizon > 0.U)
  when (tCycleAdvancing && cycleHorizon > 0.U) {
    tCycle := tCycle + 1.U
    cycleHorizon := cycleHorizon - 1.U
  }

  hCycle := hCycle + 1.U

  when (io.step.fire) {
    cycleHorizon := io.step.bits
  }
  // For now do now, do not allow the block to be stepped further, unless
  // it has gone idle
  io.step.ready := io.idle

  val crFile = genCRFile()
  // Now that we've bound registers, snoop the poke register addresses for writes
  // Yay Chisel!
  channelPokes.foreach({ case (addrs: Seq[Int], poked: Bool) =>
    poked := addrs.map(i => crFile.io.mcr.write(i).valid).reduce(_ || _)
  })

  override def genHeader(base: BigInt, sb: StringBuilder): Unit = {
    import CppGenerationUtils._

    val name = getWName.toUpperCase
    def genOffsets(signals: Seq[String]): Unit = (signals.zipWithIndex) foreach {
      case (name, idx) => sb.append(genConstStatic(name, UInt32(idx)))}

    super.genHeader(base, sb)
    val inputs = io.hPort.targetInputs
    sb.append(genComment("Pokeable target inputs"))
    sb.append(genMacro("POKE_SIZE", UInt64(inputs.size)))
    genOffsets(inputs.unzip._2)
    sb.append(genArray("INPUT_ADDRS", inputAddrs.map(off => UInt32(base + off.head)).toSeq))
    sb.append(genArray("INPUT_NAMES", inputs.unzip._2 map CStrLit))
    sb.append(genArray("INPUT_CHUNKS", inputAddrs.map(addrSeq => UInt32(addrSeq.size)).toSeq))

    val outputs = io.hPort.targetOutputs
    sb.append(genComment("Peekable target outputs"))
    sb.append(genMacro("PEEK_SIZE", UInt64(outputs.size)))
    genOffsets(outputs.unzip._2)
    sb.append(genArray("OUTPUT_ADDRS", outputAddrs.map(off => UInt32(base + off.head)).toSeq))
    sb.append(genArray("OUTPUT_NAMES", outputs.unzip._2 map CStrLit))
    sb.append(genArray("OUTPUT_CHUNKS", outputAddrs.map(addrSeq => UInt32(addrSeq.size)).toSeq))
  }
}

class PeekPokeEndpointIO(private val targetIO: Record) extends ChannelizedHostPortIO(targetIO) {
  // NB: Directions of targetIO are WRT to the endpoint, but "ins" and "outs" WRT to the target RTL
  val (targetOutputs, targetInputs, _, _) = parsePorts(targetIO)
  val outs  = targetOutputs.map({ case (field, name) => name -> InputChannel(field) })
  val ins = targetInputs.map({ case (field, name) => name -> OutputChannel(field) })
  override val elements = ListMap((ins ++ outs):_*)
  override def cloneType = new PeekPokeEndpointIO(targetIO).asInstanceOf[this.type]
}

class PeekPokeTargetIO(targetIO: Seq[(String, Data)], withReset: Boolean) extends Record {
  val reset = if (withReset) Some(Output(Bool())) else None
  override val elements = ListMap((
    reset.map("reset" -> _).toSeq ++
    targetIO.map({ case (name, field) => name -> Flipped(field.chiselCloneType) })
  ):_*)
  override def cloneType = new PeekPokeTargetIO(targetIO, withReset).asInstanceOf[this.type]
}

class PeekPokeEndpoint(targetIO: Seq[(String, Data)], reset: Option[Bool]) extends BlackBox with IsEndpoint {
  val io = IO(new PeekPokeTargetIO(targetIO, reset != None))
  val endpointIO = new PeekPokeEndpointIO(io)
  def widget = (p: Parameters) => new PeekPokeIOWidget(endpointIO)(p)
  generateAnnotations()
}

object PeekPokeEndpoint {
  @chiselName
  def apply(reset: Bool, ioList: (String, Data)*): PeekPokeEndpoint = {
    val peekPokeEndpoint = Module(new PeekPokeEndpoint(ioList, Some(reset)))
    ioList.foreach({ case (name, field) => field <> peekPokeEndpoint.io.elements(name) })
    reset := peekPokeEndpoint.io.reset.get
    peekPokeEndpoint
  }
}
