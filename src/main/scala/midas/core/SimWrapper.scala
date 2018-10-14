// See LICENSE for license details.

package midas
package core

import scala.collection.immutable.ListMap
import scala.collection.mutable.ArrayBuffer

// from rocketchip
import junctions.NastiIO
import freechips.rocketchip.amba.axi4.AXI4Bundle
import freechips.rocketchip.config.{Parameters, Field}

import chisel3._
import chisel3.util._
import chisel3.core.{Reset}
import chisel3.experimental.{MultiIOModule, Direction}
import chisel3.experimental.DataMirror.directionOf
import scala.collection.immutable.ListMap
import scala.collection.mutable.{ArrayBuffer, HashSet}

object SimUtils {
  type ChLeafType = Bits
  type ChTuple = Tuple2[ChLeafType, String]
  type RVChTuple = Tuple2[ReadyValidIO[Data], String]

  def prefixWith(prefix: String, base: Any): String =
    if (prefix != "")  s"${prefix}_${base}" else base.toString

  // Returns a list of input and output elements, with their flattened names
  def parsePorts(io: Data, prefix: String = "", alsoFlattenRVPorts: Boolean = true) = {
    val inputs = ArrayBuffer[ChTuple]()
    val outputs = ArrayBuffer[ChTuple]()
    val rvInputs = ArrayBuffer[RVChTuple]()
    val rvOutputs = ArrayBuffer[RVChTuple]()

    def loop(name: String, data: Data): Unit = data match {
      case c: Clock => // skip
      case rv: ReadyValidIO[_] => (directionOf(rv.valid): @unchecked) match {
          case Direction.Input =>  rvInputs  += (rv -> name)
          case Direction.Output => rvOutputs += (rv -> name)
        }
        if (alsoFlattenRVPorts) rv.elements foreach {case (n, e) => loop(prefixWith(name, n), e)}
      case b: Record =>
        b.elements foreach {case (n, e) => loop(prefixWith(name, n), e)}
      case v: Vec[_] =>
        v.zipWithIndex foreach {case (e, i) => loop(prefixWith(name, i), e)}
      case b: ChLeafType => (directionOf(b): @unchecked) match {
        case Direction.Input => inputs += (b -> name)
        case Direction.Output => outputs += (b -> name)
      }
    }
    loop(prefix, io)
    (inputs.toList, outputs.toList, rvInputs.toList, rvOutputs.toList)
  }
}

import SimUtils._

case object ChannelLen extends Field[Int]
case object ChannelWidth extends Field[Int]

trait HasSimWrapperParams {
  implicit val p: Parameters
  implicit val channelWidth = p(ChannelWidth)
  val traceMaxLen = p(strober.core.TraceMaxLen)
  val daisyWidth = p(strober.core.DaisyWidth)
  val sramChainNum = p(strober.core.SRAMChainNum)
}


class SimReadyValidRecord(es: Seq[(String, ReadyValidIO[Data])]) extends Record {
  val elements = ListMap() ++ (es map { case (name, rv) =>
    (directionOf(rv.valid): @unchecked) match {
      case Direction.Input => name -> Flipped(SimReadyValid(rv.bits.cloneType))
      case Direction.Output => name -> SimReadyValid(rv.bits.cloneType)
    }
  })
  def cloneType = new SimReadyValidRecord(es).asInstanceOf[this.type]
}

class ReadyValidTraceRecord(es: Seq[(String, ReadyValidIO[Data])]) extends Record {
  val elements = ListMap() ++ (es map {
    case (name, rv) => name -> ReadyValidTrace(rv.bits.cloneType)
  })
  def cloneType = new ReadyValidTraceRecord(es).asInstanceOf[this.type]
}

class AggregatedReadyValidIO[T <: Data](gen: T) extends Bundle {
  val fwd = DecoupledIO(ValidIO(gen))
  val rev = Flipped(DecoupledIO(Bool()))
  override def cloneType = new AggregatedReadyValidIO(gen).asInstanceOf[this.type]
}

object AggregatedReadyValidIO{
  def apply[T <: Data](gen: T) = new AggregatedReadyValidIO(gen.cloneType)
}

abstract class TargetChannelRecord[T <: Data](val targetIo: Seq[Data]) extends Record {
  // Generate (ChLeafType -> flatName: String) tuples that identify all of the token
  // channels on the target
  val channelizedPorts = targetIo.map({ port => port -> SimUtils.parsePorts(port, port.instanceName, false) })
  val portToChannelsMap = ListMap(channelizedPorts:_*)

  // Need a beter name for this
  val wireInputs:  Seq[ChTuple] = channelizedPorts flatMap { case (port, (is, _, _, _)) => is }
  val wireOutputs: Seq[ChTuple] = channelizedPorts flatMap { case (port, (_, os, _, _)) => os }

  // This gives a means to look up the the name of the channel that sinks or sources
  // a particular element using the original data field
  def generateChannelIO(channelDefns: Seq[ChTuple]) = channelDefns map { case (elm, name) =>
    if (directionOf(elm) == Direction.Input) {
      (name -> Flipped(Decoupled(elm.cloneType)))
    } else {
      (name -> Decoupled(elm.cloneType))
    }
  }

  val wireInputPorts = generateChannelIO(wireInputs)
  val wireOutputPorts = generateChannelIO(wireOutputs)
  // Look up the token port using the name of the channel
  val wirePortMap: ListMap[String, DecoupledIO[ChLeafType]] = ListMap((wireInputPorts ++ wireOutputPorts):_*)
  // Look up the channel name using the ChiselType associated with it
  val wireTypeMap: ListMap[ChLeafType, String] = ListMap((wireInputs ++ wireOutputs):_*)
  def wireName2port(name: String): DecoupledIO[ChLeafType] = wirePortMap(name)
  // Look up a wire port using the element from the target's port list
  def apply(chiselType: ChLeafType): DecoupledIO[ChLeafType] = wirePortMap(wireTypeMap(chiselType))

  // Ready-valid channels
  def generateRVChannelIO(channelDefns: Seq[RVChTuple]): Seq[(String, T)]

  val readyValidInputs:  Seq[RVChTuple] = channelizedPorts flatMap { case (port, (_, _, rvis, _)) => rvis }
  val readyValidOutputs: Seq[RVChTuple] = channelizedPorts flatMap { case (port, (_, _, _, rvos)) => rvos }

  lazy val readyValidInputPorts : Seq[(String, T)] = generateRVChannelIO(readyValidInputs)
  lazy val readyValidOutputPorts: Seq[(String, T)] = generateRVChannelIO(readyValidOutputs)
  lazy val readyValidInMap: ListMap[ReadyValidIO[Data], T] = ListMap((readyValidInputs.zip(readyValidInputPorts)).map({
      case (defn, port) => defn._1 -> port._2 }):_*)
  lazy val readyValidOutMap : ListMap[ReadyValidIO[Data], T] = ListMap((readyValidOutputs.zip(readyValidOutputPorts)).map({
      case (defn, port) => defn._1 -> port._2 }):_*)
  lazy val readyValidMap: ListMap[ReadyValidIO[Data], T] = readyValidInMap ++ readyValidOutMap
  // Look up a channel port using the target's RV port
  def apply(rv: ReadyValidIO[Data]): T = readyValidMap(rv)

}

class TargetBoxIO(targetIo: Seq[Data]) extends TargetChannelRecord[AggregatedReadyValidIO[Data]](targetIo) {
  val clock = Input(Clock())
  val hostReset = Input(Bool())

  def generateRVChannelIO(channelDefns: Seq[RVChTuple]) = channelDefns.map({ case (rv, name) =>
    if (directionOf(rv.valid) == Direction.Input) {
      (name -> Flipped(AggregatedReadyValidIO(rv.bits)))
    } else {
      (name -> AggregatedReadyValidIO(rv.bits))
    }
  })

  override val elements = wirePortMap ++ ListMap((
    readyValidInputPorts ++ readyValidOutputPorts ++
    // Untokenized ports
    Seq("clock" -> clock, "hostReset" -> hostReset)):_*)
  override def cloneType = new TargetBoxIO(targetIo).asInstanceOf[this.type]
}


class TargetBox(targetIo: Seq[Data]) extends BlackBox {
  val io = IO(new TargetBoxIO(targetIo))
}

class SimWrapperIO(targetPorts: Seq[Data])(implicit val p: Parameters)
    extends TargetChannelRecord[SimReadyValidIO[Data]](targetPorts) {
  import chisel3.core.ExplicitCompileOptions.NotStrict // FIXME

  ///*** Endpoints ***/
  val endpointMap = p(EndpointKey)
  val endpoints = endpointMap.endpoints
  private def findEndpoint(name: String, data: Data) {
    endpointMap get data match {
      case Some(endpoint) =>
        endpoint add (name, data)
      case None => data match {
        case b: Record => b.elements foreach {
          case (n, e) => findEndpoint(s"${name}_${n}", e)
        }
        case v: Vec[_] => v.zipWithIndex foreach {
          case (e, i) => findEndpoint(s"${name}_${i}", e)
        }
        case _ =>
      }
    }
  }
  targetPorts.foreach({ port => findEndpoint(port.instanceName, port)})

  /*** Wire Channels ***/
  val endpointWires = (endpoints flatMap (ep => (0 until ep.size) flatMap { i =>
    val (prefix, data) = ep(i)
    data.elements.toSeq flatMap {
      case (name, rv: ReadyValidIO[_]) => Nil
      case (name, wires) =>
        val (ins, outs, _, _) = SimUtils.parsePorts(wires)
        (ins ++ outs).unzip._1
    }
  })).toSet

  /*** Wire Channels ***/
  val endpointRVs = (endpoints flatMap (ep => (0 until ep.size) flatMap { i =>
      val (prefix, data) = ep(i)
      val (_, _, rvis, rvos) = SimUtils.parsePorts(data)
      (rvis ++ rvos).unzip._1
    }
  )).toSet

  // Inputs that are not target decoupled
  val pokedInputs = wireInputs filterNot (x => endpointWires(x._1))
  val peekedOutputs = wireOutputs filterNot (x => endpointWires(x._1))
  val pokedReadyValidInputs = readyValidInputs filterNot (x => endpointRVs(x._1))
  val peekedReadyValidOutputs = readyValidOutputs filterNot (x => endpointRVs(x._1))

  peekedReadyValidOutputs foreach { println(_) }
  pokedReadyValidInputs  foreach { println(_) }

  def generateRVChannelIO(channelDefns: Seq[RVChTuple]) = channelDefns.map({ case (rv, name) =>
    if (directionOf(rv.valid) == Direction.Input) {
      (name -> Flipped(SimReadyValid(rv.bits.cloneType)))
    } else {
      (name -> SimReadyValid(rv.bits.cloneType))
    }
  })

  override val elements = wirePortMap ++
    ListMap((readyValidInputPorts ++ readyValidOutputPorts):_*)
    // Untokenized ports
  override def cloneType: this.type =
    new SimWrapperIO(targetPorts).asInstanceOf[this.type]
}

class SimBox(simIo: SimWrapperIO) (implicit val p: Parameters) extends BlackBox with HasSimWrapperParams {
  val io = IO(new Bundle {
    val channelPorts = simIo.cloneType
    val reset = Input(Bool())
    val hostReset = Input(Bool())
    val clock = Input(Clock())
  })
}

class SimWrapper(targetIo: Seq[Data])(implicit val p: Parameters) extends MultiIOModule with HasSimWrapperParams {
  val channelPorts = IO(new SimWrapperIO(targetIo))
  val hostReset = IO(Input(Bool()))
  val target = Module(new TargetBox(targetIo))
  target.io.hostReset := reset.toBool && hostReset
  target.io.clock := clock

  /*** Wire Channels ***/
  def genWireChannel[T <: ChLeafType](port: T, name: String): WireChannel[T] = {
    // Figure out the clock ratio by looking up the endpoint to which this wire belongs
    //val endpointClockRatio = io.endpoints.find(_(port)) match {
    //  case Some(endpoint) => endpoint.clockRatio
    //  case None => UnityClockRatio
    //}

    // A channel is considered "flipped" if it's sunk by the tranformed RTL (sourced by an endpoint)
    val flipped = directionOf(port) == Direction.Input
    val channel = Module(new WireChannel(port.cloneType))
    channel suggestName s"WireChannel_${name}"
    if (!flipped) {
      channelPorts.elements(name) <> channel.io.out
      channel.io.in <> target.io.elements(name)
    } else {
      channel.io.in <> channelPorts.elements(name)
      target.io.elements(name) <> channel.io.out
    }
    channel.io.trace.ready := DontCare
    channel.io.traceLen := DontCare
    channel
  }
  def genWireChannel[T <: ChLeafType](arg: (T, String)): WireChannel[T] = genWireChannel(arg._1, arg._2)

  val wireInChannels:  Seq[WireChannel[ChLeafType]] = channelPorts.wireInputs.map(genWireChannel[ChLeafType])
  val wireOutChannels: Seq[WireChannel[ChLeafType]] = channelPorts.wireOutputs.map(genWireChannel[ChLeafType])

  // 1) Binds each channel's token value to the correct subfield of enq port
  // 2) Fans out fwd.hReady back out to all leaf channels
  // 3) Returns a Seq of the valids of all subfield channels -> andR to aggegrate tokens
  //def bindLeafFields(dir: Direction)(port: Data, enqField: Data, ctrl: Bool): Seq[Bool] = (port, enqField) match {
  //    case (p: Clock , f: Clock ) => throw new RuntimeException("Shouldn't have a clock type in RV channel")
  //    case (p: Record, f: Record) => ((p.elements.values).zip(f.elements.values)).flatMap(t => 
  //      bindLeafFields(dir)(t._1, t._2, ctrl)).toSeq
  //    case (p: Vec[_], f: Vec[_]) => (p.zip(f)).flatMap(t => bindLeafFields(dir)(t._1, t._2, ctrl)).toSeq
  //    // If a leaf, bind the token value to the right subfield of the enq port
  //    case (p: ChLeafType, f: ChLeafType) if directionOf(p) == dir  => {
  //      val leafCh = target.io.chiselType2port(p) // the token port coming off the transformed target
  //      if (dir == Direction.Output) {
  //        f := leafCh.bits
  //        leafCh.ready := ctrl
  //        Seq(leafCh.valid)
  //      } else {
  //        leafCh.bits := f
  //        leafCh.valid := ctrl
  //        Seq(leafCh.ready)
  //      }
  //    }
  //    case (p: ChLeafType, f: ChLeafType) => {
  //      println(s"${p.instanceName} -> ${f}")
  //      throw new RuntimeException("Illegal bits-field direction RV channel.")
  //    }
  //    case _ => throw new RuntimeException("Unexpected type in RV channel")
  //  }

  //def bindOutputLeaves = bindLeafFields(Direction.Output) _
  //def bindInputLeaves  = bindLeafFields(Direction.Input) _

  def bindOutputRVChannel[T <: Data](enq: SimReadyValidIO[T], targetPort: ReadyValidIO[T]) {
    require(directionOf(targetPort.valid) == Direction.Output, "Target must source this RV channel")
    val rvChPort = target.io(targetPort)

    enq.fwd.hValid := rvChPort.fwd.valid
    enq.target.valid := rvChPort.fwd.bits.valid
    enq.target.bits  := rvChPort.fwd.bits.bits  // Yeah, i know
    rvChPort.fwd.ready := enq.fwd.hReady

    // Connect up the target-ready token channel
    rvChPort.rev.valid := enq.rev.hValid
    rvChPort.rev.bits  := enq.target.ready
    enq.rev.hReady := rvChPort.rev.ready
  }

  def bindInputRVChannel[T <: Data](deq: SimReadyValidIO[T], targetPort: ReadyValidIO[T]) {
    require(directionOf(targetPort.valid) == Direction.Input, "Target must sink this RV channel")
    val rvChPort = target.io(targetPort)

    // Precondition: Target cannot sink some leaves or a target-valid token indepedently of the rest
    deq.fwd.hReady   := rvChPort.fwd.ready
    rvChPort.fwd.valid      := deq.fwd.hValid
    rvChPort.fwd.bits.valid := deq.target.valid
    rvChPort.fwd.bits.bits  := deq.target.bits

    // Connect up the target-ready token channel
    deq.rev.hValid := rvChPort.rev.valid
    deq.target.ready := rvChPort.rev.bits
    rvChPort.rev.ready := deq.rev.hReady
  }



  def genReadyValidChannel[T <: Data](arg: (ReadyValidIO[T], String)): ReadyValidChannel[T] = arg match {
    case (rvInterface, name) =>
      // Determine which endpoint this channel belongs to by looking it up with the valid
      //val endpointClockRatio = io.endpoints.find(_(rvInterface.valid)) match {
      //  case Some(endpoint) => endpoint.clockRatio
      //  case None => UnityClockRatio
      //}
      val endpointClockRatio = UnityClockRatio // TODO: FIXME
      // A channel is considered "flipped" if it's sunk by the tranformed RTL (sourced by an endpoint)
      val flipped = directionOf(rvInterface.valid) == Direction.Input
      val channel = Module(new ReadyValidChannel(
        rvInterface.bits.cloneType,
        flipped,
        clockRatio = if (flipped) endpointClockRatio.inverse else endpointClockRatio  
      ))

      channel.suggestName(s"ReadyValidChannel_$name")

      if (flipped) {
        channel.io.enq <> channelPorts(rvInterface)
        bindInputRVChannel(channel.io.deq, rvInterface)
      } else {
        bindOutputRVChannel(channel.io.enq, rvInterface)
        channelPorts(rvInterface) <> channel.io.deq
      }

      channel.io.trace := DontCare
      channel.io.traceLen := DontCare
      channel.io.targetReset.bits := false.B
      channel.io.targetReset.valid := true.B
      channel
  }
 /*** ReadyValid Channels ***/
 val readyValidInChannels:  Seq[ReadyValidChannel[Data]] = channelPorts.readyValidInputs map genReadyValidChannel
 val readyValidOutChannels: Seq[ReadyValidChannel[Data]] = channelPorts.readyValidOutputs map genReadyValidChannel

 // (readyValidInChannels zip io.readyValidIns.elements.unzip._2) foreach {
 //   case (channel, in) => channel.io.enq <> in }
 // (io.readyValidOuts.elements.unzip._2 zip readyValidOutChannels) foreach {
 //   case (out, channel) => out <> channel.io.deq }

 // io.readyValidInTraces.elements.unzip._2 foreach (_ := DontCare)
 // io.readyValidOutTraces.elements.unzip._2 foreach (_ := DontCare)

 // def genReadyValidChannel[T <: Data](arg: (String, ReadyValidIO[T])) =
 //   arg match { case (name, rvInterface) =>
 //       // Determine which endpoint this channel belongs to by looking it up with the valid
 //     val endpointClockRatio = io.endpoints.find(_(rvInterface.valid)) match {
 //       case Some(endpoint) => endpoint.clockRatio
 //       case None => UnityClockRatio
 //     }
 //     // A channel is considered "flipped" if it's sunk by the tranformed RTL (sourced by an endpoint)
 //     val flipped = directionOf(rvInterface.valid) == Direction.Input
 //     val channel = Module(new ReadyValidChannel(
 //       rvInterface.bits.cloneType,
 //       flipped,
 //       clockRatio = if (flipped) endpointClockRatio.inverse else endpointClockRatio  
 //     ))

 //     channel suggestName s"ReadyValidChannel_$name"

 //     if (flipped) {
 //       rvInterface <> channel.io.deq.target
 //     } else {
 //       channel.io.enq.target <> rvInterface
 //     }

 //     channel.io.trace := DontCare
 //     channel.io.targetReset.bits := targetReset
 //     channel.io.targetReset.valid := fire
 //     channel
 //   }
}
