package strober

import Chisel._

class Header extends Bundle {
  val void = Bool()
}

class Packet[T <: Bits](gen: T) extends Bundle {
  // val header  = new Header
  val payload = gen.clone
  override def clone: this.type = new Packet(gen).asInstanceOf[this.type]
}

class ChannelIO[T <: Bits](gen: T) extends Bundle {
  val in  = Decoupled(new Packet[T](gen)).flip
  val out = Decoupled(new Packet[T](gen))
}

class Channel[T <: Bits](gen: T, entries: Int = 2) extends Module {
  // Chnnel is plugged into one "flattened" IO
  // Channel has more than on FIFOs: header and payload
  // This is because it's sometimes hard to encapsulate 
  // header and payload into one packet (e.g. AXI buses)
  val io      = new ChannelIO[T](gen)
  val packets = Module(new Queue(new Packet[T](gen), entries))
  io.in <> packets.io.enq
  packets.io.deq <> io.out
}
