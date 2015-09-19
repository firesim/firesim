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
  io.in <> packets.io.enq
  packets.io.deq <> io.out
  def initTrace  = {
    // instantiate trace in the backend(lazy connection?)
    // trace queue will not appear until this is executed
    val trace = addModule(new Queue(gen, traceLen))
    trace setName "trace"
    // trace is written when a token is consumed
    trace.io.enq.bits := io.out.bits.data
    trace.io.enq.valid := io.out.fire() && trace.io.enq.ready
    trace.io.deq <> io.trace
    io.trace
  }
}
