package strober

import Chisel._
import junctions.HellaQueue
import scala.collection.immutable.ListMap
import scala.collection.mutable.{ArrayBuffer, HashMap}

case object SampleNum    extends Field[Int]
case object TraceLen     extends Field[Int]
case object ChannelLen   extends Field[Int]
case object ChannelWidth extends Field[Int]

class ChannelIO(w: Int)  extends Bundle {
  val in    = Decoupled(UInt(width=w)).flip
  val out   = Decoupled(UInt(width=w))
  val trace = Decoupled(UInt(width=w))
}

class Channel(w: Int, doTrace: Boolean = true) extends Module {
  val traceLen   = params(TraceLen)
  val channelLen = params(ChannelLen)
  val io     = new ChannelIO(w)
  val tokens = Module(new Queue(UInt(width=w), channelLen, flow=true))
  io.in <> tokens.io.enq
  tokens.io.deq <> io.out
  if (doTrace) {
    // lazy instantiation
    val trace = Module(new HellaQueue(traceLen-1)(UInt(width=w)))
    trace setName "trace"
    // trace is written when a token is consumed
    trace.io.enq.bits  := io.out.bits
    trace.io.enq.valid := io.out.fire() && trace.io.enq.ready 
    io.trace <> trace.io.deq
  }
}

object SimWrapper {
  def apply[T <: Module](c: =>T, targetParams: Parameters = Parameters.empty) = {
    val params = targetParams alter SimParams.mask
    Module(new SimWrapper(c))(params)
  }
}

class SimWrapperIO(val t_ins: Seq[(String, Bits)], val t_outs: Seq[(String, Bits)]) extends Bundle {
  val daisyWidth   = params(DaisyWidth)
  val channelWidth = params(ChannelWidth)
  val names = ((t_ins ++ t_outs) map (x => x._2 -> x._1)).toMap

  private val chunks = HashMap[Bits, Int]()
  def chunk(wire: Bits) = chunks getOrElseUpdate (wire, (wire.needWidth-1)/channelWidth+1)

  val ins   = Vec(t_ins  flatMap genPacket)
  val outs  = Vec(t_outs flatMap genPacket)
  val inT   = Vec(t_ins  flatMap (genPacket(_)(true) map (_.flip)))
  val outT  = Vec(t_outs flatMap (genPacket(_)(true)))
  val daisy = new DaisyBundle(daisyWidth)

  def genPacket[T <: Bits](arg: (String, Bits))(implicit trace: Boolean = false) = arg match {case (name, port) =>
    val packet = (0 until chunk(port)) map {i =>
      val width = scala.math.min(channelWidth, port.needWidth-i*channelWidth)
      val token = Decoupled(UInt(width=width))
      token nameIt (s"""io_${name}_${if (trace) "trace" else "channel"}_${i}""", true)
      if (port.dir == INPUT) token.flip else token
    }
    packet
  }

  lazy val inMap    = genIoMap(t_ins)
  lazy val outMap   = genIoMap(t_outs)
  lazy val inTrMap  = inMap  map {case (wire, id) => wire -> (outs.size + id)}
  lazy val outTrMap = outMap map {case (wire, id) => wire -> (outs.size + inT.size + id)}

  def genIoMap(wires: Seq[(String, Bits)]) = ListMap(((wires foldLeft (Seq[(Bits, Int)](), 0)){
    case ((map, i), (name, wire)) => (map:+(wire->i), i+chunk(wire))})._1:_*)

  def getIns(arg: (Bits, Int))  = arg match {case (wire, id) => (0 until chunk(wire)) map (off => ins(id+off))}
  def getOuts(arg: (Bits, Int)) = arg match {case (wire, id) => (0 until chunk(wire)) map (off => outs(id+off))}
  def getIns(wire: Bits) = (0 until chunk(wire)) map (off => ins(inMap(wire)+off))
  def getOuts(wire: Bits) = (0 until chunk(wire)) map (off => outs(outMap(wire)+off))
}

abstract class SimNetwork extends Module {
  def io: SimWrapperIO 
  def in_channels: Seq[Channel]
  def out_channels: Seq[Channel]
  val sampleNum    = params(SampleNum)
  val traceLen     = params(TraceLen)
  val daisyWidth   = params(DaisyWidth)
  val channelWidth = params(ChannelWidth)

  def genChannels[T <: Bits](arg: (String, T))(implicit mod: Module = this, tr: Boolean = true) = 
    arg match {case (name, port) => (0 until io.chunk(port)) map { off => 
      val width = scala.math.min(channelWidth, port.needWidth-off*channelWidth)
      val channel = mod.addModule(new Channel(width, tr)) 
      channel setName ("Channel_" + name + "_" + off)
      channel
    }}

  def connectInput[T <: Bits](i: Int, arg: (String, Bits), inChannels: Seq[Channel], fire: Option[Bool] = None) =
    arg match { case (name, wire) =>
      val channels = inChannels slice (i, i+io.chunk(wire))
      val channelOuts = wire match {
        case _: Bool => channels.head.io.out.bits.toBool
        case _ => Vec(channels map (_.io.out.bits)).toBits
      }
      fire match {
        case None => wire := channelOuts
        case Some(p) =>
          val buffer = RegEnable(channelOuts, p)
          buffer setName (name + "_buffer") 
          wire := Mux(p, channelOuts, buffer)
      }
      i + io.chunk(wire)
    }

  def connectOutput[T <: Bits](i: Int, arg: (String, Bits), outChannels: Seq[Channel]) =
    arg match { case (name, wire) =>
      val channels = outChannels slice (i, i+io.chunk(wire))
      channels.zipWithIndex foreach {case (channel, idx) =>
        channel.io.in.bits := wire.toUInt >> UInt(idx*channelWidth)
      }
      i + io.chunk(wire)
    }
}

class SimWrapper[+T <: Module](c: =>T) extends SimNetwork {
  val target = Module(c)
  val (ins, outs) = target.wires partition (_._2.dir == INPUT)

  val io = new SimWrapperIO(ins, outs)

  val fire = Wire(Bool())
  val fireNext = RegNext(fire)

  val in_channels:  Seq[Channel] = ins flatMap genChannels
  val out_channels: Seq[Channel] = outs flatMap genChannels

  // Datapath: Channels <> IOs
  (in_channels zip io.ins) foreach {case (channel, in) => channel.io.in <> in}
  (ins foldLeft 0)(connectInput(_, _, in_channels, Some(fire))) 

  (out_channels zip io.outs) foreach {case (channel, out) => channel.io.out <> out}
  (outs foldLeft 0)(connectOutput(_, _, out_channels))

  (in_channels  zip io.inT)  foreach {case (channel, trace) => channel.io.trace <> trace}
  (out_channels zip io.outT) foreach {case (channel, trace) => channel.io.trace <> trace}
  
  // Control
  // Firing condtion:
  // 1) all input values are valid
  // 2) all output FIFOs are not full
  fire := (in_channels foldLeft Bool(true))(_ && _.io.out.valid) && 
          (out_channels foldLeft Bool(true))(_ && _.io.in.ready)
 
  // Inputs are consumed when firing conditions are met
  in_channels foreach (_.io.out.ready := fire)
   
  // Outputs should be ready after one cycle
  out_channels foreach (_.io.in.valid := fireNext || RegNext(reset))

  transforms.init(this, fire)
}
