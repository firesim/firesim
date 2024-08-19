// See LICENSE for license details.

package firesim.lib.bridges

import scala.collection.immutable.ListMap

import chisel3._

import firesim.lib.bridgeutils._
import firesim.lib.bridgeutils.SimUtils._
import firesim.lib.bridgeutils.SerializationUtils._

case class PeekPokeKey(
  peeks:                Seq[SerializableField],
  pokes:                Seq[SerializableField],
  maxChannelDecoupling: Int = 2,
)

object PeekPokeKey {
  def apply(targetIO: Record): PeekPokeKey = {
    val (targetInputs, targetOutputs, _, _) = parsePorts(targetIO)
    val inputFields                         = targetInputs.map({ case (field, name) => SerializableField(name, field) })
    val outputFields                        = targetOutputs.map({ case (field, name) => SerializableField(name, field) })
    PeekPokeKey(inputFields, outputFields)
  }
}

class PeekPokeTokenizedIO(private val targetIO: PeekPokeTargetIO) extends Record with ChannelizedHostPortIO {
  //NB: Directions of targetIO are WRT to the bridge, but "ins" and "outs" WRT to the target RTL
  def targetClockRef                      = targetIO.clock
  val (targetOutputs, targetInputs, _, _) = parsePorts(targetIO)
  val outs                                = targetOutputs.map({ case (field, name) => name -> InputChannel(field) })
  val ins                                 = targetInputs.map({ case (field, name) => name -> OutputChannel(field) })
  override val elements                   = ListMap((ins ++ outs): _*)
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
      val io  = IO(new RegeneratedTargetIO(key.pokes, key.peeks))
      // This reconstitutes the targetIO in the target-side of the bridge
      val tIO = IO(new PeekPokeTargetIO(io.elements.toSeq))
      io  <> DontCare
      tIO <> DontCare
    })
    dummyModule.io <> DontCare
    dummyModule.tIO <> DontCare
    new PeekPokeTokenizedIO(dummyModule.tIO)
  }
}

class PeekPokeTargetIO(targetIO: Seq[(String, Data)]) extends Record {
  val clock             = Input(Clock())
  override val elements = ListMap(
    (
      Seq("clock" -> clock) ++
        targetIO.map({ case (name, field) => name -> Flipped(chiselTypeOf(field)) })
    ): _*
  )
}

class PeekPokeBridge(targetIO: Seq[(String, Data)]) extends BlackBox with Bridge[PeekPokeTokenizedIO] {
  val moduleName     = "midas.widgets.PeekPokeBridgeModule"
  val io             = IO(new PeekPokeTargetIO(targetIO))
  val constructorArg = Some(PeekPokeKey(io))
  val bridgeIO       = new PeekPokeTokenizedIO(io)
  generateAnnotations()
}

object PeekPokeBridge {
  def apply(clock: Clock, reset: Bool, ioList: (String, Data)*): PeekPokeBridge = {
    // Hack: Specify the direction on the wire so that the bridge can correctly
    // infer it will be poked.
    val directionedReset = Wire(Input(Bool()))
    val completeIOList   = ("reset", directionedReset) +: ioList
    val peekPokeBridge   = Module(new PeekPokeBridge(completeIOList))
    completeIOList.foreach({ case (name, field) => field <> peekPokeBridge.io.elements(name) })
    peekPokeBridge.io.clock := clock
    reset := directionedReset
    peekPokeBridge
  }
}
