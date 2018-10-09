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
import chisel3.core.{ActualDirection, Reset}
import chisel3.core.DataMirror.directionOf
import chisel3.experimental.MultiIOModule
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
        case ActualDirection.Input => inputs += (b -> name)
        case ActualDirection.Output => outputs += (b -> name)
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
      case ActualDirection.Input => name -> Flipped(SimReadyValid(rv.bits.cloneType))
      case ActualDirection.Output => name -> SimReadyValid(rv.bits.cloneType)
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

  // Need a beter name for this
  val inputs:  Seq[ChTuple] = channelizedPorts flatMap { case (port, (is, os)) => is }
  val outputs: Seq[ChTuple] = channelizedPorts flatMap { case (port, (is, os)) => os }

  // This gives a means to look up the the name of the channel that sinks or sources
  // a particular element using the original data field
  def generateChannelIO(channelDefns: Seq[ChTuple]) = channelDefns map { case (elm, name) =>
    if (directionOf(elm) == ActualDirection.Input) {
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
  //private def findEndpoint(name: String, data: Data) {
  //  endpointMap get data match {
  //    case Some(endpoint) =>
  //      endpoint add (name, data)
  //    case None => data match {
  //      case b: Record => b.elements foreach {
  //        case (n, e) => findEndpoint(s"${name}_${n}", e)
  //      }
  //      case v: Vec[_] => v.zipWithIndex foreach {
  //        case (e, i) => findEndpoint(s"${name}_${i}", e)
  //      }
  //      case _ =>
  //    }
  //  }
  //}
  //io.elements.foreach({ case (name, data) => findEndpoint(name, data)})

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
  //val readyValidInputs = endpoints flatMap (ep => (0 until ep.size) flatMap { i =>
  //  val (prefix, data) = ep(i)
  //  data.elements.toSeq collect { case (name, rv: ReadyValidIO[_])
  //    if directionOf(rv.valid) == ActualDirection.Input => s"${prefix}_${name}" -> rv
  //  }
  //})
  //val readyValidOutputs = endpoints flatMap (ep => (0 until ep.size) flatMap { i =>
  //  val (prefix, data) = ep(i)
  //  data.elements.toSeq collect { case (name, rv: ReadyValidIO[_])
  //    if directionOf(rv.valid) == ActualDirection.Output => s"${prefix}_${name}" -> rv
  //  }
  //})
  //// FIXME: aggregate doesn't have a type without leaf
  //val readyValidIns =
  //  if (readyValidInputs.nonEmpty) new SimReadyValidRecord(readyValidInputs)
  //  else Input(new SimReadyValidRecord(readyValidInputs))
  //val readyValidOuts =
  //  if (readyValidOutputs.nonEmpty) new SimReadyValidRecord(readyValidOutputs)
  //  else Output(new SimReadyValidRecord(readyValidOutputs))

  //val readyValidInMap = (readyValidInputs.unzip._2 zip readyValidIns.elements).toMap
  //val readyValidOutMap = (readyValidOutputs.unzip._2 zip readyValidOuts.elements).toMap
  //val readyValidMap = readyValidInMap ++ readyValidOutMap

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
    val flipped = directionOf(port) == ActualDirection.Input
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

  val wireInChannels:  Seq[WireChannel[ChLeafType]] = channelPorts.inputs.map(genWireChannel[ChLeafType])
  val wireOutChannels: Seq[WireChannel[ChLeafType]] = channelPorts.outputs.map(genWireChannel[ChLeafType])


 // /*** ReadyValid Channels ***/
 // val readyValidInChannels: Seq[ReadyValidChannel[_]] = io.readyValidInputs map genReadyValidChannel
 // val readyValidOutChannels: Seq[ReadyValidChannel[_]] = io.readyValidOutputs map genReadyValidChannel

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
 //     val flipped = directionOf(rvInterface.valid) == ActualDirection.Input
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
