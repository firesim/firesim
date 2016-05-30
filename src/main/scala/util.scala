package strober

import Chisel._
import scala.collection.immutable.ListMap
import scala.collection.mutable.ArrayBuffer

object SimUtils {
  def parsePorts(io: Data, reset: Option[Bool] = None) = {
    val inputs = ArrayBuffer[(Bits, String)]()
    val outputs = ArrayBuffer[(Bits, String)]()
    def loop(name: String, data: Data): Unit = data match {
      case b: Bundle => b.elements foreach {case (n, e) => loop(s"${name}_${n}", e)}
      case v: Vec[_] => v.zipWithIndex foreach {case (e, i) => loop(s"${name}_${i}", e)}
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

  def genIoMap(ports: Seq[(Bits, String)])(implicit channelWidth: Int) =
    ((ports foldLeft ((ListMap[Bits, Int](), 0))){
      case ((map, off), (port, name)) => (map + (port -> off), off + getChunks(port))
    })._1

 def genChannels[T <: Bits](arg: (T, String))
      (implicit p: cde.Parameters, trace: Boolean = true) = {
    implicit val channelWidth = p(ChannelWidth)
    arg match { case (port, name) => (0 until getChunks(port)) map { off =>
      val width = scala.math.min(channelWidth, port.getWidth - off * channelWidth)
      val channel = Module(new Channel(width, trace))
      channel suggestName s"Channel_${name}_${off}"
      channel
    }}
  }

  def connectInput[T <: Bits](off: Int, arg: (Bits, String), inChannels: Seq[Channel], fire: Bool)
      (implicit channelWidth: Int) = arg match { case (wire, name) =>
    val channels = inChannels slice (off, off + getChunks(wire))
    val channelOuts = wire match {
      case _: Bool => channels.head.io.out.bits.toBool
      case _ => Vec(channels map (_.io.out.bits)).toBits
    }
    val buffer = RegEnable(channelOuts, fire)
    buffer suggestName (name + "_buffer")
    wire := Mux(fire, channelOuts, buffer)
    off + getChunks(wire)
  }

  def connectOutput[T <: Bits](off: Int, arg: (Bits, String), outChannels: Seq[Channel])
      (implicit channelWidth: Int) = arg match { case (wire, name) =>
    val channels = outChannels slice (off, off + getChunks(wire))
    channels.zipWithIndex foreach {case (channel, i) =>
      channel.io.in.bits := wire.asUInt >> UInt(i * channelWidth)
    }
    off + getChunks(wire)
  }
}

// vim: set ts=4 sw=4 et:
