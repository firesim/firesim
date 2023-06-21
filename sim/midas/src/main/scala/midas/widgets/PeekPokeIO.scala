// See LICENSE for license details.

package midas
package widgets

import scala.collection.immutable.ListMap
import scala.collection.mutable

import chisel3._
import chisel3.util._ // Deprecated
import org.chipsalliance.cde.config.Parameters

import midas.core.SimUtils._
import midas.widgets.SerializationUtils._

class PeekPokeWidgetIO(implicit val p: Parameters) extends WidgetIO()(p) {
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
  lazy val module = new BridgeModuleImp(this) {
    val io = IO(new PeekPokeWidgetIO)
    val hPort = IO(PeekPokeTokenizedIO(key))

    require(key.maxChannelDecoupling > 1, "A smaller channel decoupling will affect FMR")
    // Tracks the number of tokens the slowest channel has to produce or consume
    // before we reach the desired target cycle
    val cycleHorizon = RegInit(0.U(ctrlWidth.W))
    val tCycleName = "tCycle"
    val tCycle = genWideRORegInit(0.U(64.W), tCycleName, false)
    val tCycleAdvancing = WireInit(false.B)

    // Simulation control.
    val step = Wire(Decoupled(UInt(p(CtrlNastiKey).dataBits.W)))
    genAndAttachQueue(step, "STEP")
    val done = Wire(Input(Bool()))
    genRORegInit(done, "DONE", 0.U)

    done := cycleHorizon === 0.U

    def genWideReg(name: String, field: Bits): Seq[UInt] = Seq.tabulate(
        (field.getWidth + ctrlWidth - 1) / ctrlWidth)({ i =>
      val chunkWidth = math.min(ctrlWidth, field.getWidth - (i * ctrlWidth))
      Reg(UInt(chunkWidth.W)).suggestName(s"target_${name}_{i}")
    })

    // Asserted by a channel when it is advancing or has advanced ahead of tCycle
    val channelDecouplingFlags = mutable.ArrayBuffer[Bool]()
    val outputPrecisePeekableFlags = mutable.ArrayBuffer[Bool]()
    val channelPokes           = mutable.ArrayBuffer[(Seq[Int], Bool)]()

    def bindInputs(name: String, channel: DecoupledIO[Bits]): Seq[Int] = {
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

      val regAddrs = reg.zipWithIndex.map({ case (chunk, idx) => attach(chunk,  s"${name}_${idx}", ReadWrite, false) })
      channelDecouplingFlags += isAhead
      channelPokes += regAddrs -> poke
      regAddrs
    }

    def bindOutputs(name: String, channel: DecoupledIO[Bits]): Seq[Int] = {
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
      outputPrecisePeekableFlags += cyclesAhead.value === 1.U
      reg.zipWithIndex.map({ case (chunk, idx) => attach(chunk,  s"${name}_${idx}", ReadOnly, false) })
    }

    val inputAddrs = hPort.ins.map(elm => bindInputs(elm._1, elm._2))
    val outputAddrs = hPort.outs.map(elm => bindOutputs(elm._1, elm._2))

    // Every output one token "ahead" (current cycle value available) <=> entire PeekPoke state "precisely" peekable
    genRORegInit((done +: outputPrecisePeekableFlags).reduce(_ && _), "PRECISE_PEEKABLE", 0.U)

    val tCycleWouldAdvance = channelDecouplingFlags.reduce(_ && _)
    // tCycleWouldAdvance will be asserted if all inputs have been poked; but only increment
    // tCycle if we've been asked to step (cycleHorizon > 0.U)
    when (tCycleWouldAdvance && cycleHorizon > 0.U) {
      tCycle := tCycle + 1.U
      cycleHorizon := cycleHorizon - 1.U
      tCycleAdvancing := true.B
    }

    when (step.fire) {
      cycleHorizon := step.bits
    }
    // Do not allow the block to be stepped further, unless it has gone idle
    step.ready := done

    val crFile = genCRFile()
    // Now that we've bound registers, snoop the poke register addresses for writes
    // Yay Chisel!
    channelPokes.foreach({ case (addrs: Seq[Int], poked: Bool) =>
      poked := addrs.map(i => crFile.io.mcr.activeWriteToAddress(i)).reduce(_ || _)
    })

    override def genHeader(base: BigInt, memoryRegions: Map[String, BigInt], sb: StringBuilder): Unit = {
      def genPortLists(names: Seq[String], addrs: Seq[Seq[Int]]): CPPLiteral = {
        StdMap("peek_poke_t::Port", names.zip(addrs).map({ case (name, addr) =>
          name -> CppStruct("peek_poke_t::Port", Seq(
            "address" -> UInt64(base + addr.head),
            "chunks" -> UInt32(addr.size)
          ))
        }))
      }

      genConstructor(
          base,
          sb,
          "peek_poke_t",
          "peek_poke",
          Seq(
            genPortLists(hPort.ins.unzip._1, inputAddrs),
            genPortLists(hPort.outs.unzip._1, outputAddrs)
          )
      )
    }
  }
}

class PeekPokeTokenizedIO(private val targetIO: PeekPokeTargetIO) extends Record with ChannelizedHostPortIO {
  //NB: Directions of targetIO are WRT to the bridge, but "ins" and "outs" WRT to the target RTL
  def targetClockRef = targetIO.clock
  val (targetOutputs, targetInputs, _, _) = parsePorts(targetIO)
  val outs  = targetOutputs.map({ case (field, name) => name -> InputChannel(field) })
  val ins = targetInputs.map({ case (field, name) => name -> OutputChannel(field) })
  override val elements = ListMap((ins ++ outs):_*)
}

object PeekPokeTokenizedIO {
  // Hack: Since we can't build the host-land port from a copy of the targetIO
  // (we cannot currently serialize that) Spoof the original targetIO using
  // serialiable port information
  def apply(key: PeekPokeKey): PeekPokeTokenizedIO = {
    // Instantiate a useless module from which we can get a hardware type with parsePorts
    val dummyModule = Module(new Module {
      // This spoofs the sources that were passed to the companion object ioList
      // pokes and peeks are reversed because PeekPokeTargetIO is going to flip them
      val io = IO(new RegeneratedTargetIO(key.pokes, key.peeks))
      // This reconstitutes the targetIO in the target-side of the bridge
      val tIO = IO(new PeekPokeTargetIO(io.elements.toSeq))
      io <> DontCare
      tIO <> DontCare
    })
    dummyModule.io <> DontCare
    dummyModule.tIO <> DontCare
    new PeekPokeTokenizedIO(dummyModule.tIO)
  }
}

class PeekPokeTargetIO(targetIO: Seq[(String, Data)]) extends Record {
  val clock = Input(Clock())
  override val elements = ListMap((
    Seq("clock" -> clock) ++
    targetIO.map({ case (name, field) => name -> Flipped(chiselTypeOf(field)) })
  ):_*)
}

class PeekPokeBridge(targetIO: Seq[(String, Data)]) extends BlackBox
    with Bridge[PeekPokeTokenizedIO, PeekPokeBridgeModule] {
  val io = IO(new PeekPokeTargetIO(targetIO))
  val constructorArg = Some(PeekPokeKey(io))
  val bridgeIO = new PeekPokeTokenizedIO(io)
  generateAnnotations()
}

object PeekPokeBridge {
  def apply(clock: Clock, reset: Bool, ioList: (String, Data)*): PeekPokeBridge = {
    // Hack: Specify the direction on the wire so that the bridge can correctly
    // infer it will be poked.
    val directionedReset = Wire(Input(Bool()))
    val completeIOList = ("reset", directionedReset) +: ioList
    val peekPokeBridge = Module(new PeekPokeBridge(completeIOList))
    completeIOList.foreach({ case (name, field) => field <> peekPokeBridge.io.elements(name) })
    peekPokeBridge.io.clock := clock
    reset := directionedReset
    peekPokeBridge
  }
}
