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

  // Returns a list of input and output elements, with their flattened names
  def parsePorts(io: Data, prefix: String = "") = {
    val inputs = ArrayBuffer[ChTuple]()
    val outputs = ArrayBuffer[ChTuple]()

    def prefixWith(prefix: String, base: Any): String =
      if (prefix != "")  s"${prefix}_${base}" else base.toString

    def loop(name: String, data: Data): Unit = data match {
      case c: Clock => // skip
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
    (inputs.toList, outputs.toList)
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


class TargetChannelRecord(val targetIo: Seq[Data]) extends Record {
  // Generate (ChLeafType -> flatName: String) tuples that identify all of the token
  // channels on the target
  val channelizedPorts = targetIo.map({ port => port -> SimUtils.parsePorts(port, port.instanceName) })
  val portToChannelsMap = ListMap(channelizedPorts:_*)

  // Creates a mapping from RV
  val targetDecoupledPorts = targetIo.map({ port => port -> SimUtils.parsePorts(port, port.instanceName) })
  // Need a beter name for this
  val inputs:  Seq[ChTuple] = channelizedPorts flatMap { case (port, (is, os)) => is }
  val outputs: Seq[ChTuple] = channelizedPorts flatMap { case (port, (is, os)) => os }

  // This gives a means to look up the the name of the channel that sinks or sources
  // a particular element using the original data field
  def generateChannelIO(channelDefns: Seq[ChTuple]) = channelDefns map { case (elm, name) =>
    if (directionOf(elm) == Direction.Input) {
      (name -> Flipped(Decoupled(elm.cloneType)))
    } else {
      (name -> Decoupled(elm.cloneType))
    }
  }

  val inputPorts = generateChannelIO(inputs)
  val outputPorts = generateChannelIO(outputs)
  // Look up the token port using the name of the channel
  val portMap: ListMap[String, DecoupledIO[ChLeafType]] = ListMap((inputPorts ++ outputPorts):_*)
  // Look up the channel name using the ChiselType associated with it
  val typeMap: ListMap[ChLeafType, String] = ListMap((inputs ++ outputs):_*)
  def name2port(name: String): DecoupledIO[ChLeafType] = portMap(name)
  def chiselType2port(chiselType: ChLeafType): DecoupledIO[ChLeafType] = portMap(typeMap(chiselType))

  val elements: ListMap[String, Data] = portMap
  def cloneType = new TargetChannelRecord(targetIo).asInstanceOf[this.type]
}

class TargetBoxIO(targetIo: Seq[Data]) extends TargetChannelRecord(targetIo) {
  val clock = Input(Clock())
  val hostReset = Input(Bool())
  override val elements = portMap ++ ListMap((
    // Untokenized ports
    Seq("clock" -> clock, "hostReset" -> hostReset)):_*)
  override def cloneType = new TargetBoxIO(targetIo).asInstanceOf[this.type]
}


class TargetBox(targetIo: Seq[Data]) extends BlackBox {
  val io = IO(new TargetBoxIO(targetIo))
}

class SimWrapperIO(targetPorts: Seq[Data])(implicit val p: Parameters) extends TargetChannelRecord(targetPorts) {
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
        val (ins, outs) = SimUtils.parsePorts(wires)
        (ins ++ outs).unzip._1
    }
  })).toSet

  // Inputs that are not target decoupled
  val wireInputs = inputs filterNot { case (wire, name) =>
    (endpoints exists (_(wire))) && !endpointWires(wire) }
  val wireOutputs = outputs filterNot { case (wire, name) =>
    (endpoints exists (_(wire))) && !endpointWires(wire) }
  val pokedInputs = wireInputs filterNot (x => endpointWires(x._1))
  val peekedOutputs = wireOutputs filterNot (x => endpointWires(x._1))

  val wireInputPorts : Seq[(String, DecoupledIO[ChLeafType])] = generateChannelIO(wireInputs)
  val wireOutputPorts: Seq[(String, DecoupledIO[ChLeafType])] = generateChannelIO(wireOutputs)
  val wirePortMap: ListMap[String, DecoupledIO[ChLeafType]] = ListMap((wireInputPorts ++ wireOutputPorts):_*)
  // Look up a wire port using the element from the target's port list
  def apply(elm: ChLeafType) = wirePortMap(typeMap(elm))


  //// FIXME: aggregate doesn't have a type without leaf
  //val wireIns =
  //  if (inWireChannelNum > 0) Flipped(Vec(inWireChannelNum, Decoupled(UInt(channelWidth.W))))
  //  else Input(Vec(inWireChannelNum, Decoupled(UInt(channelWidth.W))))
  ////
  //// FIXME: aggregate doesn't have a type without leaf
  //val wireOuts =
  //  if (outWireChannelNum > 0) Vec(outWireChannelNum, Decoupled(UInt(channelWidth.W)))
  //  else Output(Vec(outWireChannelNum, Decoupled(UInt(channelWidth.W))))
  ////
  //val wireInMap: Map[String, DecoupledIO[Element]] = ListMap((wireInputs.map({{ case(wire, name) => name ->  )
  //val wireOutMap = genIoMap(wireOutputs)

  //def getOut(arg: ChLeafType): DecoupledIO[ChLeafType] = chiselType2port(arg)
  //def getIn(arg: ChLeafType): DecoupledIO[ChLeafType] = chiselType2port(arg)

  ///*** ReadyValid Channels ***/
  //
  def generateRVChannelIO(channelDefns: Seq[(String, ReadyValidIO[Data])]) = channelDefns.map({ case (name, rv) =>
    if (directionOf(rv.valid) == Direction.Input) {
      (name -> Flipped(SimReadyValid(rv.bits.cloneType)))
    } else {
      (name -> SimReadyValid(rv.bits.cloneType))
    }
  })

  val readyValidInputs: Seq[(String, ReadyValidIO[Data])] = endpoints.flatMap(_.readyValidInputs)
  val readyValidOutputs: Seq[(String, ReadyValidIO[Data])] = endpoints.flatMap(_.readyValidOutputs)
  val readyValidInputPorts:  Seq[(String, SimReadyValidIO[Data])] = generateRVChannelIO(readyValidInputs)
  val readyValidOutputPorts: Seq[(String, SimReadyValidIO[Data])] = generateRVChannelIO(readyValidOutputs)
  val readyValidPortMap = ListMap((readyValidInputPorts ++ readyValidOutputPorts):_*)

  val readyValidInMap = ListMap((readyValidInputs.zip(readyValidInputPorts)).map({
      case (defn, port) => defn._2 -> port._2 }):_*)
  val readyValidOutMap = ListMap((readyValidOutputs.zip(readyValidOutputPorts)).map({
      case (defn, port) => defn._2 -> port._2 }):_*)
  val readyValidMap: ListMap[ReadyValidIO[Data], SimReadyValidIO[Data]] = readyValidInMap ++ readyValidOutMap

  // Look up a channel port using the target's RV port
  def apply(rv: ReadyValidIO[Data]) = readyValidMap(rv)

  override val elements = wirePortMap ++ readyValidPortMap
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
  target.io.hostReset := hostReset
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
  def bindLeafFields(dir: Direction)(port: Data, enqField: Data, ctrl: Bool): Seq[Bool] = (port, enqField) match {
      case (p: Clock , f: Clock ) => throw new RuntimeException("Shouldn't have a clock type in RV channel")
      case (p: Record, f: Record) => ((p.elements.values).zip(f.elements.values)).flatMap(t => 
        bindLeafFields(dir)(t._1, t._2, ctrl)).toSeq
      case (p: Vec[_], f: Vec[_]) => (p.zip(f)).flatMap(t => bindLeafFields(dir)(t._1, t._2, ctrl)).toSeq
      // If a leaf, bind the token value to the right subfield of the enq port
      case (p: ChLeafType, f: ChLeafType) if directionOf(p) == dir  => {
        val leafCh = target.io.chiselType2port(p) // the token port coming off the transformed target
        if (dir == Direction.Output) {
          f := leafCh.bits
          leafCh.ready := ctrl
          Seq(leafCh.valid)
        } else {
          leafCh.bits := f
          leafCh.valid := ctrl
          Seq(leafCh.ready)
        }
      }
      case (p: ChLeafType, f: ChLeafType) => {
        println(s"${p.instanceName} -> ${f}")
        throw new RuntimeException("Illegal bits-field direction RV channel.")
      }
      case _ => throw new RuntimeException("Unexpected type in RV channel")
    }

  def bindOutputLeaves = bindLeafFields(Direction.Output) _
  def bindInputLeaves  = bindLeafFields(Direction.Input) _

  // Takes LI-BDN-complaint channels coming off the transformed target and binds them
  // to the legacy channel interface. ~ Aggregates N + 2 leaf channels into a bidirectional token channel
  def bindOutputRVChannel[T <: Data](enq: SimReadyValidIO[T], targetPort: ReadyValidIO[T]) {
    require(directionOf(targetPort.valid) == Direction.Output, "Target must source this RV channel")
    val validCh = target.io.chiselType2port(targetPort.valid)
    val readyCh = target.io.chiselType2port(targetPort.ready)

    // And reduce all of leaf-field channels and valid channel to populate the fwd token
    // NOTE: Channel consumes all field tokens in the same cycle -> readies depend on valid
    val leavesAllValid = bindOutputLeaves(targetPort.bits, enq.target.bits, enq.fwd.hReady).reduce(_ && _)
    enq.fwd.hValid := validCh.valid && leavesAllValid
    enq.target.valid := validCh.bits
    validCh.ready := enq.fwd.hReady

    // Connect up the target-ready token channel
    enq.rev.hReady := readyCh.ready
    readyCh.valid  := enq.rev.hValid
    readyCh.bits   := enq.target.ready
  }

  def bindInputRVChannel[T <: Data](deq: SimReadyValidIO[T], targetPort: ReadyValidIO[T]) {
    require(directionOf(targetPort.valid) == Direction.Input, "Target must sink this RV channel")
    val validCh = target.io.chiselType2port(targetPort.valid)
    val readyCh = target.io.chiselType2port(targetPort.ready)

    // And reduce all of leaf-field channels and valid channel to populate the fwd token
    val inputReadies = bindInputLeaves(targetPort.bits, deq.target.bits, deq.fwd.hValid)
    val allReady = inputReadies.reduce(_ && _) && validCh.ready
    val someReady = inputReadies.reduce(_ || _) || validCh.ready

    // Precondition: Target cannot sink some leaves or a target-valid token indepedently of the rest
    assert(!deq.fwd.hValid || !(someReady && !allReady),
      "Subchannels must consume output token in unison")

    deq.fwd.hReady   := allReady
    validCh.bits     := deq.target.valid
    validCh.valid    := deq.fwd.hValid

    // Connect up the target-ready token channel
    deq.rev.hValid   :=  readyCh.valid
    deq.target.ready :=  readyCh.bits
    readyCh.ready    :=  deq.rev.hReady
  }



  def genReadyValidChannel[T <: Data](arg: (String, ReadyValidIO[T])): ReadyValidChannel[T] = arg match {
    case (name, rvInterface) =>
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
 val readyValidInChannels: Seq[ReadyValidChannel[Data]] = channelPorts.readyValidInputs map genReadyValidChannel
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
