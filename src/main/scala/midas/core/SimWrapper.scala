package midas
package core

// from rocketchip
import junctions.NastiIO
import uncore.axi4.AXI4Bundle

import chisel3._
import chisel3.util._
import config.{Parameters, Field}
import SimUtils._
import scala.collection.immutable.ListMap
import scala.collection.mutable.{ArrayBuffer, HashSet}

trait Endpoint[T <: Data] {
  protected val channels = ArrayBuffer[T]()
  protected val wires = HashSet[Bits]()
  def size = channels.size
  def zipWithIndex = channels.toList.zipWithIndex
  def apply(wire: Bits) = wires(wire)
  def apply(channel: T) = channels contains channel
  def apply(i: Int): T = channels(i)
  def add(channel: T) {
    val (ins, outs) = SimUtils.parsePorts(channel)
    wires ++= ins.unzip._1
    wires ++= outs.unzip._1
    channels += channel
  }
}
class SimNastiMemIO extends Endpoint[NastiIO] {
  override def toString = "nasti"
}
class SimAXI4MemIO extends Endpoint[AXI4Bundle] {
  override def toString = "axi4"
}

object SimUtils {
  def parsePorts(io: Data, reset: Option[Bool] = None) = {
    val inputs = ArrayBuffer[(Bits, String)]()
    val outputs = ArrayBuffer[(Bits, String)]()
    def loop(name: String, data: Data): Unit = data match {
      case b: Bundle =>
        b.elements foreach {case (n, e) => loop(s"${name}_${n}", e)}
      case v: Vec[_] =>
        v.zipWithIndex foreach {case (e, i) => loop(s"${name}_${i}", e)}
      case b: Bits if b.dir == INPUT => inputs += (b -> name)
      case b: Bits if b.dir == OUTPUT => outputs += (b -> name)
      case _ => // skip
    }
    reset match {
      case None =>
      case Some(r) => inputs += (r -> "reset")
    }
    loop("io", io)
    (inputs.toList, outputs.toList)
  }

  def getChunks(b: Bits)(implicit channelWidth: Int): Int =
    (b.getWidth-1)/channelWidth + 1
  def getChunks(s: Seq[Bits])(implicit channelWidth: Int): Int =
    (s foldLeft 0)((res, b) => res + getChunks(b))
  def getChunks(args: (Bits, String))(implicit channelWidth: Int): (String, Int) =
    args match { case (wire, name) => name -> SimUtils.getChunks(wire) }
}

case object TraceMaxLen extends Field[Int]
case object ChannelLen extends Field[Int]
case object ChannelWidth extends Field[Int]
case object SRAMChainNum extends Field[Int]

trait HasSimWrapperParams {
  implicit val p: Parameters
  implicit val channelWidth = p(ChannelWidth)
  val traceMaxLen = p(TraceMaxLen)
  val daisyWidth = p(DaisyWidth)
  val sramChainNum = p(SRAMChainNum)
  val enableSnapshot = p(EnableSnapshot)
}

class SimWrapperIO(io: Data, reset: Bool)
                  (implicit val p: Parameters) extends Bundle with HasSimWrapperParams {
  /*** Endpoints ***/
  val nasti = new SimNastiMemIO
  val axi4 = new SimAXI4MemIO
  val endpoints = Seq(nasti, axi4)
  private def findEndpoint(data: Data): Unit = data match {
    case m: NastiIO if m.w.valid.dir == OUTPUT =>
      nasti add m
    case m: AXI4Bundle if m.w.valid.dir == OUTPUT =>
      axi4 add m
    case b: Bundle =>
      b.elements.unzip._2 foreach findEndpoint
    case v: Vec[_] =>
      v.toSeq foreach findEndpoint
    case _ =>
  }
  findEndpoint(io)

  val (inputs, outputs) = parsePorts(io, Some(reset))

  /*** Wire Channels ***/
  val wireInputs = inputs filterNot (x => endpoints exists (_(x._1)))
  val wireOutputs = outputs filterNot (x => endpoints exists (_(x._1)))
  val inWireChannelNum = getChunks(wireInputs.unzip._1)
  val outWireChannelNum = getChunks(wireOutputs.unzip._1)
  val wireIns = Flipped(Vec(inWireChannelNum, Decoupled(UInt(channelWidth.W))))
  val wireOuts = Vec(outWireChannelNum, Decoupled(UInt(channelWidth.W)))

  /*** ReadyValid Channels ***/
  val readyValidInputs = endpoints flatMap (ep => (0 until ep.size) flatMap { i =>
    ep(i).elements.toSeq collect { case (name, rv: ReadyValidIO[_]) if rv.valid.dir == INPUT =>
      s"${ep}_${i}_${name}" -> rv
    }
  })
  val readyValidOutputs = endpoints flatMap (ep => (0 until ep.size) flatMap { i =>
    ep(i).elements.toSeq collect { case (name, rv: ReadyValidIO[_]) if rv.valid.dir == OUTPUT =>
      s"${ep}_${i}_${name}" -> rv
    }
  })

  class ReadyValidRecord(elems: Seq[(String, ReadyValidIO[Data])]) extends Record {
    val elements = ListMap((elems map {
      case (name, rv) if rv.valid.dir == INPUT => name -> Flipped(SimReadyValid(rv.bits))
      case (name, rv) if rv.valid.dir == OUTPUT => name -> SimReadyValid(rv.bits)
    }):_*)
    def cloneType = new ReadyValidRecord(elems).asInstanceOf[this.type]
  }
  val readyValidIns = new ReadyValidRecord(readyValidInputs)
  val readyValidOuts = new ReadyValidRecord(readyValidOutputs)
  val readyValidInMap = (readyValidInputs.unzip._2 zip readyValidIns.elements).toMap
  val readyValidOutMap = (readyValidOutputs.unzip._2 zip readyValidOuts.elements).toMap
  val readyValidMap = readyValidInMap ++ readyValidOutMap

  /*** Instrumentation ***/
  val daisy = new DaisyBundle(daisyWidth, sramChainNum)
  val traceLen = Input(UInt(log2Up(traceMaxLen + 1).W))
  val wireInTraces = Vec(if (enableSnapshot) inWireChannelNum else 0, Decoupled(UInt(channelWidth.W)))
  val wireOutTraces = Vec(if (enableSnapshot) outWireChannelNum else 0, Decoupled(UInt(channelWidth.W)))
  // TODO: ReadyValidIO Traces

  override def cloneType: this.type =
    new SimWrapperIO(io, reset).asInstanceOf[this.type]
}

class TargetBox(targetIo: Data) extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val io = targetIo.cloneType
  })
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

class SimWrapper(targetIo: Data)
                (implicit val p: Parameters) extends Module with HasSimWrapperParams {
  val target = Module(new TargetBox(targetIo))
  val io = IO(new SimWrapperIO(target.io.io, target.io.reset))
  val fire = Wire(Bool())

  target.io.clock := clock

  /*** Wire Channels ***/
  val wireInChannels: Seq[WireChannel] = io.wireInputs flatMap genWireChannels
  val wireOutChannels: Seq[WireChannel] = io.wireOutputs flatMap genWireChannels

  (wireInChannels zip io.wireIns) foreach { case (channel, in) => channel.io.in <> in }
  (io.wireInputs foldLeft 0)(connectInput(_, _, wireInChannels, fire))

  (io.wireOuts zip wireOutChannels) foreach { case (out, channel) => out <> channel.io.out }
  (io.wireOutputs foldLeft 0)(connectOutput(_, _, wireOutChannels))

  (io.wireInTraces zip wireInChannels) foreach { case (tr, channel) => tr <> channel.io.trace }
  (io.wireOutTraces zip wireOutChannels) foreach { case (tr, channel) => tr <> channel.io.trace }

  def genWireChannels[T <: Bits](arg: (T, String)) =
    arg match { case (port, name) =>
      (0 until getChunks(port)) map { off =>
        val width = scala.math.min(channelWidth, port.getWidth - off * channelWidth)
        val channel = Module(new WireChannel(width))
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

  /*** ReadyValid Channels ***/
  val readyValidInChannels: Seq[ReadyValidChannel[_]] = io.readyValidInputs map genReadyValidChannel
  val readyValidOutChannels: Seq[ReadyValidChannel[_]] = io.readyValidOutputs map genReadyValidChannel

  (readyValidInChannels zip io.readyValidIns.elements.unzip._2) foreach {
    case (channel, in) => channel.io.enq <> in }
  (io.readyValidOuts.elements.unzip._2 zip readyValidOutChannels) foreach {
    case (out, channel) => out <> channel.io.deq }

  def genReadyValidChannel[T <: Data](arg: (String, ReadyValidIO[T])) =
    arg match { case (name, io) =>
      val channel = Module(new ReadyValidChannel(io.bits))
      channel suggestName s"ReadyValidChannel_$name"
      (io.valid.dir: @unchecked) match {
        case INPUT  => io <> channel.io.deq.target
        case OUTPUT => channel.io.enq.target <> io
      }
      channel.io.targetReset.bits := target.io.reset
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
   
  // Outputs should be ready when firing conditions are met
  val resetNext = RegNext(reset)
  wireOutChannels foreach (_.io.in.valid := fire || resetNext)
  readyValidOutChannels foreach (_.io.enq.host.hValid := fire || resetNext)

  // Trace size is runtime configurable
  wireInChannels foreach (_.io.traceLen := io.traceLen)
  wireOutChannels foreach (_.io.traceLen := io.traceLen)

  // Cycles for debug
  val cycles = Reg(UInt(64.W))
  when (fire) {
    cycles := Mux(target.io.reset, UInt(0), cycles + UInt(1))
  }
} 
