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
import SimUtils._
import scala.collection.immutable.ListMap
import scala.collection.mutable.{ArrayBuffer, HashSet}

object SimUtils {
  def parsePorts(io: Data, prefix: String = "") = {
    val inputs = ArrayBuffer[(Bits, String)]()
    val outputs = ArrayBuffer[(Bits, String)]()

    def prefixWith(prefix: String, base: Any): String =
      if (prefix != "")  s"${prefix}_${base}" else base.toString

    def loop(name: String, data: Data): Unit = data match {
      case c: Clock => // skip
      case b: Record =>
        b.elements foreach {case (n, e) => loop(prefixWith(name, n), e)}
      case v: Vec[_] =>
        v.zipWithIndex foreach {case (e, i) => loop(prefixWith(name, i), e)}
      case b: Bits => (directionOf(b): @unchecked) match {
        case ActualDirection.Input => inputs += (b -> name)
        case ActualDirection.Output => outputs += (b -> name)
      }
    }
    loop(prefix, io)
    (inputs.toList, outputs.toList)
  }

  def getChunks(b: Bits)(implicit channelWidth: Int): Int =
    (b.getWidth-1)/channelWidth + 1
  def getChunks(s: Seq[Bits])(implicit channelWidth: Int): Int =
    (s foldLeft 0)((res, b) => res + getChunks(b))
  def getChunks(args: (Bits, String))(implicit channelWidth: Int): (String, Int) =
    args match { case (wire, name) => name -> SimUtils.getChunks(wire) }

  def genIoMap(ports: Seq[(Bits, String)], offset: Int = 0)(implicit channelWidth: Int) =
    ((ports foldLeft ((ListMap[Bits, Int](), offset))){
      case ((map, off), (port, name)) => (map + (port -> off), off + getChunks(port))
    })._1
}

case object ChannelLen extends Field[Int]
case object ChannelWidth extends Field[Int]

trait HasSimWrapperParams {
  implicit val p: Parameters
  implicit val channelWidth = p(ChannelWidth)
  val traceMaxLen = p(strober.core.TraceMaxLen)
  val daisyWidth = p(strober.core.DaisyWidth)
  val sramChainNum = p(strober.core.SRAMChainNum)
  val enableSnapshot = p(EnableSnapshot)
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

class SimWrapperIO(io: TargetBoxIO)
   (implicit val p: Parameters) extends Bundle with HasSimWrapperParams {
  import chisel3.core.ExplicitCompileOptions.NotStrict // FIXME

  /*** Endpoints ***/
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
  io.elements.foreach({ case (name, data) => findEndpoint(name, data)})

  val (inputs, outputs) = parsePorts(io)

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
  val wireInputs = inputs filterNot { case (wire, name) =>
    (endpoints exists (_(wire))) && !endpointWires(wire) }
  val wireOutputs = outputs filterNot { case (wire, name) =>
    (endpoints exists (_(wire))) && !endpointWires(wire) }
  val pokedInputs = wireInputs filterNot (x => endpointWires(x._1))
  val peekedOutputs = wireOutputs filterNot (x => endpointWires(x._1))
  val inWireChannelNum = getChunks(wireInputs.unzip._1)
  val outWireChannelNum = getChunks(wireOutputs.unzip._1)
  // FIXME: aggregate doesn't have a type without leaf
  val wireIns =
    if (inWireChannelNum > 0) Flipped(Vec(inWireChannelNum, Decoupled(UInt(channelWidth.W))))
    else Input(Vec(inWireChannelNum, Decoupled(UInt(channelWidth.W))))
  //
  // FIXME: aggregate doesn't have a type without leaf
  val wireOuts =
    if (outWireChannelNum > 0) Vec(outWireChannelNum, Decoupled(UInt(channelWidth.W)))
    else Output(Vec(outWireChannelNum, Decoupled(UInt(channelWidth.W))))
  //
  val wireInMap = genIoMap(wireInputs)
  val wireOutMap = genIoMap(wireOutputs)
  def getIns(arg: (Bits, Int)): Seq[DecoupledIO[UInt]] = arg match {
    case (wire, id) => (0 until getChunks(wire)) map (off => wireIns(id+off))
  }
  def getOuts(arg: (Bits, Int)): Seq[DecoupledIO[UInt]] = arg match {
    case (wire, id) => (0 until getChunks(wire)) map (off => wireOuts(id+off))
  }
  def getIns(wire: Bits): Seq[DecoupledIO[UInt]] = getIns(wire -> wireInMap(wire))
  def getOuts(wire: Bits): Seq[DecoupledIO[UInt]] = getOuts(wire -> wireOutMap(wire))

  /*** ReadyValid Channels ***/
  val readyValidInputs = endpoints flatMap (ep => (0 until ep.size) flatMap { i =>
    val (prefix, data) = ep(i)
    data.elements.toSeq collect { case (name, rv: ReadyValidIO[_])
      if directionOf(rv.valid) == ActualDirection.Input => s"${prefix}_${name}" -> rv
    }
  })
  val readyValidOutputs = endpoints flatMap (ep => (0 until ep.size) flatMap { i =>
    val (prefix, data) = ep(i)
    data.elements.toSeq collect { case (name, rv: ReadyValidIO[_])
      if directionOf(rv.valid) == ActualDirection.Output => s"${prefix}_${name}" -> rv
    }
  })
  // FIXME: aggregate doesn't have a type without leaf
  val readyValidIns =
    if (readyValidInputs.nonEmpty) new SimReadyValidRecord(readyValidInputs)
    else Input(new SimReadyValidRecord(readyValidInputs))
  val readyValidOuts =
    if (readyValidOutputs.nonEmpty) new SimReadyValidRecord(readyValidOutputs)
    else Output(new SimReadyValidRecord(readyValidOutputs))
  //
  val readyValidInMap = (readyValidInputs.unzip._2 zip readyValidIns.elements).toMap
  val readyValidOutMap = (readyValidOutputs.unzip._2 zip readyValidOuts.elements).toMap
  val readyValidMap = readyValidInMap ++ readyValidOutMap

  /*** Instrumentation ***/
  val daisy = new strober.core.DaisyBundle(daisyWidth, sramChainNum)
  val traceLen = Input(UInt(log2Up(traceMaxLen + 1).W))
  val wireInTraces = 
    if (inWireChannelNum > 0) Vec(inWireChannelNum, Decoupled(UInt(channelWidth.W)))
    else Output(Vec(inWireChannelNum, Decoupled(UInt(channelWidth.W))))
  val wireOutTraces =
    if (outWireChannelNum > 0) Vec(outWireChannelNum, Decoupled(UInt(channelWidth.W)))
    else Output(Vec(outWireChannelNum, Decoupled(UInt(channelWidth.W))))
  //

  // FIXME: aggregate doesn't have a type without leaf
  val readyValidInTraces =
    if (readyValidOutputs.nonEmpty) new ReadyValidTraceRecord(readyValidInputs)
    else Output(new ReadyValidTraceRecord(readyValidInputs))
  val readyValidOutTraces =
    if (readyValidOutputs.nonEmpty) new ReadyValidTraceRecord(readyValidOutputs)
    else Output(new ReadyValidTraceRecord(readyValidOutputs))
  //

  override def cloneType: this.type =
    new SimWrapperIO(io).asInstanceOf[this.type]
}

class TargetBoxIO(targetIo: Seq[(String, Data)]) extends Record {
  // ChiselTypeOf is more strict; rely on chiselCloneType behavior defaulting
  // to output for pass-added target-IO for now
  val elements = ListMap((targetIo map { case (name, field) => name -> field.chiselCloneType}):_*)
  def resets = elements collect { case (_, r: Reset) => r }
  def clocks = elements collect { case (_, c: Clock) => c }
  def cloneType = new TargetBoxIO(targetIo).asInstanceOf[this.type]
}

// this gets replaced with the real target
class TargetBox(targetIo: Seq[(String, Data)]) extends BlackBox {
  val io = IO(new TargetBoxIO(targetIo))
}

class SimBox(simIo: SimWrapperIO)
            (implicit val p: Parameters)
             extends BlackBox with HasSimWrapperParams {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val io = simIo.cloneType
  })
}

class SimWrapper(targetIo: Seq[(String, Data)], generatedTargetIo: Seq[(String, Data)])
                (implicit val p: Parameters) extends Module with HasSimWrapperParams {
  val target = Module(new TargetBox(targetIo ++ generatedTargetIo))
  val io = IO(new SimWrapperIO(target.io))
  val fire = Wire(Bool())

  target.io.clocks foreach (_ := clock)

  val targetResets = ArrayBuffer[UInt]()

  /*** Wire Channels ***/
  val wireInChannels: Seq[WireChannel] = io.wireInputs flatMap genWireChannels
  val wireOutChannels: Seq[WireChannel] = io.wireOutputs flatMap genWireChannels

  (wireInChannels zip io.wireIns) foreach { case (channel, in) => channel.io.in <> in }
  (io.wireInputs foldLeft 0)(connectInput(_, _, wireInChannels, fire))

  (io.wireOuts zip wireOutChannels) foreach { case (out, channel) => out <> channel.io.out }
  (io.wireOutputs foldLeft 0)(connectOutput(_, _, wireOutChannels))

  if (enableSnapshot) {
    (io.wireInTraces zip wireInChannels) foreach { case (tr, channel) => tr <> channel.io.trace }
    (io.wireOutTraces zip wireOutChannels) foreach { case (tr, channel) => tr <> channel.io.trace }
  } else {
    io.wireInTraces foreach (_ := DontCare)
    io.wireOutTraces foreach (_ := DontCare)
  }

  def genWireChannels[T <: Bits](arg: (T, String)) =
    arg match { case (port, name) =>
      (0 until getChunks(port)) map { off =>
        val width = scala.math.min(channelWidth, port.getWidth - off * channelWidth)
        // Figure out the clock ratio by looking up the endpoint to which this wire belongs
        val endpointClockRatio = io.endpoints.find(_(port)) match {
          case Some(endpoint) => endpoint.clockRatio
          case None => UnityClockRatio
        }

        // A channel is considered "flipped" if it's sunk by the tranformed RTL (sourced by an endpoint)
        val flipped = directionOf(port) == ActualDirection.Input
        val channel = Module(new WireChannel(
          width,
          clockRatio = if (flipped) endpointClockRatio.inverse else endpointClockRatio
        ))
        // FIXME: it's not working
        /* port match {
          case _: Reset =>
            targetResets += channel.io.out.bits
          case _ =>
        } */
        if (!enableSnapshot) channel.io.trace := DontCare
        if (name == "reset") targetResets += channel.io.out.bits // FIXME: it's awkward
        channel suggestName s"WireChannel_${name}_${off}"
        channel
      }
    }

  def connectInput[T <: Bits](off: Int, arg: (Bits, String), inChannels: Seq[WireChannel], fire: Bool) =
    arg match { case (wire, name) =>
      val channels = inChannels slice (off, off + getChunks(wire))
      wire := Cat(channels.reverse map (_.io.out.bits))
      off + getChunks(wire)
    }

  def connectOutput[T <: Bits](off: Int, arg: (Bits, String), outChannels: Seq[WireChannel]) =
    arg match { case (wire, name) =>
      val channels = outChannels slice (off, off + getChunks(wire))
      channels.zipWithIndex foreach { case (channel, i) =>
        channel.io.in.bits := wire.asUInt() >> (i * channelWidth)
      }
      off + getChunks(wire)
    }

  val targetReset = (targetResets foldLeft 0.U)(_ | _)

  /*** ReadyValid Channels ***/
  val readyValidInChannels: Seq[ReadyValidChannel[_]] = io.readyValidInputs map genReadyValidChannel
  val readyValidOutChannels: Seq[ReadyValidChannel[_]] = io.readyValidOutputs map genReadyValidChannel

  (readyValidInChannels zip io.readyValidIns.elements.unzip._2) foreach {
    case (channel, in) => channel.io.enq <> in }
  (io.readyValidOuts.elements.unzip._2 zip readyValidOutChannels) foreach {
    case (out, channel) => out <> channel.io.deq }

  if (enableSnapshot) {
    (io.readyValidInTraces.elements.unzip._2 zip readyValidInChannels) foreach {
      case (tr, channel) => tr <> channel.io.trace }
    (io.readyValidOutTraces.elements.unzip._2 zip readyValidOutChannels) foreach {
      case (tr, channel) => tr <> channel.io.trace }
  } else {
    io.readyValidInTraces.elements.unzip._2 foreach (_ := DontCare)
    io.readyValidOutTraces.elements.unzip._2 foreach (_ := DontCare)
  }

  def genReadyValidChannel[T <: Data](arg: (String, ReadyValidIO[T])) =
    arg match { case (name, rvInterface) =>
        // Determine which endpoint this channel belongs to by looking it up with the valid
      val endpointClockRatio = io.endpoints.find(_(rvInterface.valid)) match {
        case Some(endpoint) => endpoint.clockRatio
        case None => UnityClockRatio
      }
      // A channel is considered "flipped" if it's sunk by the tranformed RTL (sourced by an endpoint)
      val flipped = directionOf(rvInterface.valid) == ActualDirection.Input
      val channel = ReadyValidChannel(
        rvInterface.bits.cloneType,
        flipped,
        clockRatio = if (flipped) endpointClockRatio.inverse else endpointClockRatio  
      )

      channel suggestName s"ReadyValidChannel_$name"

      if (flipped) {
        rvInterface <> channel.io.deq.target
      } else {
        channel.io.enq.target <> rvInterface
      }

      if (!enableSnapshot) channel.io.trace := DontCare
      channel.io.targetReset.bits := targetReset
      channel.io.targetReset.valid := fire
      channel
    }

  // Control
  // Firing condtion:
  // 1) all input values are valid
  // 2) all output FIFOs are not full
  fire := (wireInChannels foldLeft true.B)(_ && _.io.out.valid) &&
          (wireOutChannels foldLeft true.B)(_ && _.io.in.ready) &&
          (readyValidInChannels foldLeft true.B)(_ && _.io.deq.host.hValid) &&
          (readyValidOutChannels foldLeft true.B)(_ && _.io.enq.host.hReady)

  // Inputs are consumed when firing conditions are met
  wireInChannels foreach (_.io.out.ready := fire)
  readyValidInChannels foreach (_.io.deq.host.hReady := fire)

  // Outputs should be ready when firing conditions are met, inject an intial
  // token into each output queue after reset is asserted
  val resetNext = RegNext(reset.toBool)
  wireOutChannels foreach (_.io.in.valid := fire || resetNext)
  readyValidOutChannels foreach (_.io.enq.host.hValid := fire || resetNext)

  // Trace size is runtime configurable
  wireInChannels foreach (_.io.traceLen := io.traceLen)
  wireOutChannels foreach (_.io.traceLen := io.traceLen)
  readyValidInChannels foreach (_.io.traceLen := io.traceLen)
  readyValidOutChannels foreach (_.io.traceLen := io.traceLen)

  io.daisy := DontCare // init daisy output

  // Cycles for debug
  val cycles = Reg(UInt(64.W))
  when (fire) {
    // cycles := Mux(target.io.reset, 0.U, cycles + 1.U)
    when(false.B) { printf("%d", cycles) }
  }
}
