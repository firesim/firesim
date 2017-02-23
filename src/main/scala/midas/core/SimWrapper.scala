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
class SimNastiMemIO extends Endpoint[NastiIO]
class SimAXI4MemIO extends Endpoint[AXI4Bundle]

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

class SimWrapperIO(io: Data, reset: Bool)(implicit val p: Parameters)
    extends _root_.util.ParameterizedBundle()(p) with HasSimWrapperParams {
  val (inputs, outputs) = parsePorts(io, Some(reset))
  val inChannelNum = getChunks(inputs.unzip._1)
  val outChannelNum = getChunks(outputs.unzip._1)

  val ins = Flipped(Vec(inChannelNum, Decoupled(UInt(channelWidth.W))))
  val outs = Vec(outChannelNum, Decoupled(UInt(channelWidth.W)))
  val inT = Vec(if (enableSnapshot) inChannelNum else 0, Decoupled(UInt(channelWidth.W)))
  val outT = Vec(if (enableSnapshot) outChannelNum else 0, Decoupled(UInt(channelWidth.W)))
  val daisy = new DaisyBundle(daisyWidth, sramChainNum)
  val traceLen = Input(UInt(log2Up(traceMaxLen + 1).W))

  lazy val inMap = genIoMap(inputs)
  lazy val outMap = genIoMap(outputs)
  lazy val inTrMap = genIoMap(inputs, outs.size)
  lazy val outTrMap = genIoMap(outputs, outs.size + inT.size)

  def genIoMap(ports: Seq[(Bits, String)], offset: Int = 0)(implicit channelWidth: Int) =
    ((ports foldLeft ((ListMap[Bits, Int](), offset))){
      case ((map, off), (port, name)) => (map + (port -> off), off + getChunks(port))
    })._1

  def getIns(arg: (Bits, Int)): Seq[DecoupledIO[UInt]] = arg match {
    case (wire, id) => (0 until getChunks(wire)) map (off => ins(id+off))
  }
  def getOuts(arg: (Bits, Int)): Seq[DecoupledIO[UInt]] = arg match {
    case (wire, id) => (0 until getChunks(wire)) map (off => outs(id+off))
  }

  def getIns(wire: Bits): Seq[DecoupledIO[UInt]] = getIns(wire -> inMap(wire))
  def getIns(name: String): Seq[DecoupledIO[UInt]] = {
    val (wire, matchedName) = inputs.filter(_._2 == name).head
    getIns(wire)
  }

  def getOuts(wire: Bits): Seq[DecoupledIO[UInt]] = getOuts(wire -> outMap(wire))
  def getOuts(name: String): Seq[DecoupledIO[UInt]] = {
    val (wire, matchedName) = outputs.filter(_._2 == name).head
    getOuts(wire)
  }

  val nasti = new SimNastiMemIO
  val axi4 = new SimAXI4MemIO
  val endpoints = Seq(nasti, axi4)
  private def findEndpoint(data: Data): Unit = data match {
    case m: NastiIO if m.w.valid.dir == OUTPUT =>
      nasti add m
    case m: AXI4Bundle if m.w.valid.dir == OUTPUT =>
      axi4 add m
    case b: Bundle => b.elements.unzip._2 foreach findEndpoint
    case v: Vec[_] => v.toSeq foreach findEndpoint
    case _ =>
  }
  findEndpoint(io)

  val pokedIns = inputs filterNot (x => endpoints exists (_(x._1)))
  val peekedOuts = outputs filterNot (x => endpoints exists (_(x._1)))

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

  def genChannels[T <: Bits](arg: (T, String))(implicit p: config.Parameters) = {
    implicit val channelWidth = p(ChannelWidth)
    arg match { case (port, name) => (0 until getChunks(port)) map { off =>
      val width = scala.math.min(channelWidth, port.getWidth - off * channelWidth)
      val channel = Module(new Channel(width))
      channel suggestName s"Channel_${name}_${off}"
      channel
    }}
  }

  def connectInput[T <: Bits](off: Int, arg: (Bits, String), inChannels: Seq[Channel], fire: Bool)
      (implicit channelWidth: Int) = arg match { case (wire, name) =>
    val channels = inChannels slice (off, off + getChunks(wire))
    val channelOuts = Cat(channels.reverse map (_.io.out.bits))
    val buffer = RegEnable(channelOuts, fire)
    buffer suggestName (name + "_buffer")
    wire := Mux(fire, channelOuts, buffer)
    off + getChunks(wire)
  }

  def connectOutput[T <: Bits](off: Int, arg: (Bits, String), outChannels: Seq[Channel], reset: Bool)
      (implicit channelWidth: Int) = arg match { case (wire, name) =>
    val channels = outChannels slice (off, off + getChunks(wire))
    channels.zipWithIndex foreach {case (channel, i) =>
      channel.io.in.bits := Mux(reset, UInt(0), wire.asUInt >> UInt(i * channelWidth))
    }
    off + getChunks(wire)
  }

  val inChannels: Seq[Channel] = io.inputs flatMap genChannels
  val outChannels: Seq[Channel] = io.outputs flatMap genChannels

  target.io.clock := clock

  // Datapath: Channels <> IOs
  (inChannels zip io.ins) foreach {case (channel, in) => channel.io.in <> in}
  (io.inputs foldLeft 0)(connectInput(_, _, inChannels, fire))

  (io.outs zip outChannels) foreach {case (out, channel) => out <> channel.io.out}
  (io.outputs foldLeft 0)(connectOutput(_, _, outChannels, target.io.reset))

  if (enableSnapshot) {
    (io.inT zip inChannels) foreach {case (trace, channel) => trace <> channel.io.trace}
    (io.outT zip outChannels) foreach {case (trace, channel) => trace <> channel.io.trace}
  }
  
  // Control
  // Firing condtion:
  // 1) all input values are valid
  // 2) all output FIFOs are not full
  fire := (inChannels foldLeft Bool(true))(_ && _.io.out.valid) && 
          (outChannels foldLeft Bool(true))(_ && _.io.in.ready)
 
  // Inputs are consumed when firing conditions are met
  inChannels foreach (_.io.out.ready := fire)
   
  // Outputs should be ready when firing conditions are met
  outChannels foreach (_.io.in.valid := fire || RegNext(reset))

  // Trace size is runtime configurable
  inChannels foreach (_.io.traceLen := io.traceLen)
  outChannels foreach (_.io.traceLen := io.traceLen)

  // Cycles for debug
  val cycles = Reg(UInt(64.W))
  when (fire) {
    cycles := Mux(target.io.reset, UInt(0), cycles + UInt(1))
  }
} 
