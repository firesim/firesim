package strober

import Chisel._

class Header extends Bundle {
  val void = Bool()
}

class Packet[T <: Bits](gen: T) extends Bundle {
  // val header  = new Header
  val data = gen.clone
  override def clone: this.type = new Packet(gen).asInstanceOf[this.type]
}

class ChannelIO[T <: Bits](gen: T) extends Bundle {
  val in  = Decoupled(new Packet[T](gen)).flip
  val out = Decoupled(new Packet[T](gen))
  val trace = Decoupled(gen)
}

class Channel[T <: Bits](gen: T, entries: Int = 2) extends Module {
  // Chnnel is plugged into one "flattened" IO
  val traceLen = params(TraceLen)
  val io       = new ChannelIO[T](gen)
  val packets  = Module(new Queue(new Packet[T](gen), entries))
  val trace    = Module(new Queue(gen, traceLen))
  io.in <> packets.io.enq
  packets.io.deq <> io.out
  def initTrace  = {
    // instantiate trace in the backend(lazy connection?)
    // trace queue will not appear until this is executed
    trace.io.enq.bits := io.in.bits.data
    trace.io.enq.valid := io.in.valid && trace.io.enq.ready
    trace.io.deq <> io.trace
    io.trace
  }
}
