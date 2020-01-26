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
import midas.widgets.SerializationUtils._

class PeekPokeWidgetIO(implicit val p: Parameters) extends WidgetIO()(p) {
  val step = Flipped(Decoupled(UInt(ctrl.nastiXDataBits.W)))
  val idle = Output(Bool())
}

case class PeekPokeKey(
    peeks: Seq[SerializableField],
    pokes: Seq[SerializableField],
    maxChannelDecoupling: Int = 2)

object PeekPokeKey {
  def apply(targetIO: Record): PeekPokeKey = {
    val (targetInputs, targetOutputs, _, _)  = parsePorts(targetIO)
    val inputFields = targetInputs.map({ case (field, name) => SerializableField(name, field) })
    val outputFields = targetOutputs.map({ case (field, name) => SerializableField(name, field) })
    PeekPokeKey(inputFields, outputFields)
  }
}

// Maximum channel decoupling puts a bound on the number of cycles the fastest
// channel can advance ahead of the slowest channel
class PeekPokeBridgeModule(key: PeekPokeKey)(implicit p: Parameters) extends BridgeModule[PeekPokeTokenizedIO] {
  val io = IO(new PeekPokeWidgetIO)
  val hPort = IO(PeekPokeTokenizedIO(key))

  require(key.maxChannelDecoupling > 1, "A smaller channel decoupling will affect FMR")
  // Tracks the number of tokens the slowest channel has to produce or consume
  // before we reach the desired target cycle
  val cycleHorizon = RegInit(0.U(ctrlWidth.W))
  val tCycleName = "tCycle"
  val tCycle = genWideRORegInit(0.U(64.W), tCycleName)
  val tCycleAdvancing = WireInit(false.B)

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

  @chiselName
  def bindInputs(name: String, channel: DecoupledIO[ChLeafType]): Seq[Int] = {
    val reg = genWideReg(name, channel.bits)
    // Track local-channel decoupling
    val cyclesAhead = SatUpDownCounter(key.maxChannelDecoupling)
    val isAhead = !cyclesAhead.empty || channel.fire
    cyclesAhead.inc := channel.fire
    cyclesAhead.dec := tCycleAdvancing

    // If a channel is being poked, allow it to enqueue it's output token
    // This lets us peek outputs that depend combinationally on inputs we poke
    // poke will be asserted when a memory-mapped register associated with
    // this channel is being written to
    val poke = Wire(Bool()).suggestName(s"${name}_poke")
    // Handle the fields > 32 bits
    val wordsReceived = RegInit(0.U(log2Ceil(reg.size + 1).W))
    val advanceViaPoke = wordsReceived === reg.size.U

    when (poke) {
      wordsReceived := wordsReceived + 1.U
    }.elsewhen(channel.fire) {
      wordsReceived := 0.U
    }

    channel.bits := Cat(reg.reverse).asTypeOf(channel.bits)
    channel.valid := !cyclesAhead.full && cyclesAhead.value < cycleHorizon || advanceViaPoke

    val regAddrs = reg.zipWithIndex.map({ case (chunk, idx) => attach(chunk,  s"${name}_${idx}", ReadWrite) })
    channelDecouplingFlags += isAhead
    channelPokes += regAddrs -> poke
    regAddrs
  }

  @chiselName
  def bindOutputs(name: String, channel: DecoupledIO[ChLeafType]): Seq[Int] = {
    val reg = genWideReg(name, channel.bits)
    // Track local-channel decoupling
    val cyclesAhead = SatUpDownCounter(key.maxChannelDecoupling)
    val isAhead = !cyclesAhead.empty || channel.fire
    cyclesAhead.inc := channel.fire
    cyclesAhead.dec := tCycleAdvancing

    // Let token sinks accept one more token than sources can produce (if
    // they aren't poked) This enables peeking outputs that depend
    // combinationally on other input channels (these channels may not
    // necessarily sourced (poked) by this bridge)
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

  val inputAddrs = hPort.ins.map(elm => bindInputs(elm._1, elm._2))
  val outputAddrs = hPort.outs.map(elm => bindOutputs(elm._1, elm._2))

  val tCycleWouldAdvance = channelDecouplingFlags.reduce(_ && _)
  // tCycleWouldAdvance will be asserted if all inputs have been poked; but only increment
  // tCycle if we've been asked to step (cycleHorizon > 0.U)
  when (tCycleWouldAdvance && cycleHorizon > 0.U) {
    tCycle := tCycle + 1.U
    cycleHorizon := cycleHorizon - 1.U
    tCycleAdvancing := true.B
  }

  hCycle := hCycle + 1.U

  when (io.step.fire) {
    cycleHorizon := io.step.bits
  }
  // Do not allow the block to be stepped further, unless it has gone idle
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
    sb.append(genComment("Pokeable target inputs"))
    sb.append(genMacro("POKE_SIZE", UInt64(hPort.ins.size)))
    genOffsets(hPort.ins.unzip._1)
    sb.append(genArray("INPUT_ADDRS", inputAddrs.map(off => UInt32(base + off.head)).toSeq))
    sb.append(genArray("INPUT_NAMES", hPort.ins.unzip._1 map CStrLit))
    sb.append(genArray("INPUT_CHUNKS", inputAddrs.map(addrSeq => UInt32(addrSeq.size)).toSeq))

    sb.append(genComment("Peekable target outputs"))
    sb.append(genMacro("PEEK_SIZE", UInt64(hPort.outs.size)))
    genOffsets(hPort.outs.unzip._1)
    sb.append(genArray("OUTPUT_ADDRS", outputAddrs.map(off => UInt32(base + off.head)).toSeq))
    sb.append(genArray("OUTPUT_NAMES", hPort.outs.unzip._1 map CStrLit))
    sb.append(genArray("OUTPUT_CHUNKS", outputAddrs.map(addrSeq => UInt32(addrSeq.size)).toSeq))
  }
}

class PeekPokeTokenizedIO(private val targetIO: Record) extends ChannelizedHostPortIO(targetIO) {
  //NB: Directions of targetIO are WRT to the bridge, but "ins" and "outs" WRT to the target RTL
  val (targetOutputs, targetInputs, _, _) = parsePorts(targetIO)
  val outs  = targetOutputs.map({ case (field, name) => name -> InputChannel(field) })
  val ins = targetInputs.map({ case (field, name) => name -> OutputChannel(field) })
  override val elements = ListMap((ins ++ outs):_*)
  override def cloneType = new PeekPokeTokenizedIO(targetIO).asInstanceOf[this.type]
}

object PeekPokeTokenizedIO {
  // Hack: Since we can't build the host-land port from a copy of the targetIO
  // (we cannot currently serialize that) Spoof the original targetIO using
  // serialiable port information
  def apply(key: PeekPokeKey): PeekPokeTokenizedIO = {
    // Instantiate a useless module from which we can get a hardware type with parsePorts
    val dummyModule = Module(new Module {
      val io = IO(new RegeneratedTargetIO(key.peeks, key.pokes))
      io <> DontCare
    })
    dummyModule.io <> DontCare
    new PeekPokeTokenizedIO(dummyModule.io)
  }
}

class PeekPokeTargetIO(targetIO: Seq[(String, Data)], withReset: Boolean) extends Record {
  val reset = if (withReset) Some(Output(Bool())) else None
  override val elements = ListMap((
    reset.map("reset" -> _).toSeq ++
    targetIO.map({ case (name, field) => name -> Flipped(chiselTypeOf(field)) })
  ):_*)
  override def cloneType = new PeekPokeTargetIO(targetIO, withReset).asInstanceOf[this.type]
}

class PeekPokeBridge(targetIO: Seq[(String, Data)], reset: Option[Bool]) extends BlackBox
    with Bridge[PeekPokeTokenizedIO, PeekPokeBridgeModule] {
  val io = IO(new PeekPokeTargetIO(targetIO, reset != None))
  val constructorArg = Some(PeekPokeKey(io))
  val bridgeIO = new PeekPokeTokenizedIO(io)
  generateAnnotations()
}

object PeekPokeBridge {
  @chiselName
  def apply(reset: Bool, ioList: (String, Data)*): PeekPokeBridge = {
    val peekPokeBridge = Module(new PeekPokeBridge(ioList, Some(reset)))
    ioList.foreach({ case (name, field) => field <> peekPokeBridge.io.elements(name) })
    reset := peekPokeBridge.io.reset.get
    peekPokeBridge
  }
}
