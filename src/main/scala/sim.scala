package strober

import Chisel._
import scala.collection.immutable.ListMap

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

object SimWrapper {
  def apply[T <: Module](c: =>T, targetParams: Parameters = Parameters.empty) = {
    val params = targetParams alter SimParams.mask
    Module(new SimWrapper(c))(params)
  }
}

class SimWrapperIO(t_ins: Array[(String, Bits)], t_outs: Array[(String, Bits)]) extends Bundle {
  val daisyWidth = params(DaisyWidth)
  def genPacket[T <: Bits](arg: (String, Bits)) = arg match { case (name, port) =>
    val packet = Decoupled(new Packet(port))
    if (port.dir == INPUT) packet.flip
    packet nameIt ("io_" + name + "_channel", true)
    packet
  }
  val ins = Vec(t_ins map genPacket)
  val outs = Vec(t_outs map genPacket)
  val inMap = ListMap((t_ins.unzip._2 zip ins):_*)
  val outMap = ListMap((t_outs.unzip._2 zip outs):_*)
  val daisy = new DaisyBundle(daisyWidth)
}

abstract class SimNetwork extends Module {
  def io: SimWrapperIO 
  def in_channels: Seq[Channel[Bits]]
  def out_channels: Seq[Channel[Bits]]
  val sampleNum  = params(SampleNum)
  val traceLen   = params(TraceLen)
  val daisyWidth = params(DaisyWidth)

  def initTrace[T <: Bits](arg: (T, Int)) = arg match { case (gen, id) =>
    val trace = addPin(Decoupled(gen), gen.name + "_trace")
    val channel = if (gen.dir == INPUT) in_channels(id) else out_channels(id)
    trace <> channel.initTrace
    (trace, gen)
  }
}

class SimWrapper[+T <: Module](c: =>T) extends SimNetwork {
  val target = Module(c)
  val (ins, outs) = target.wires partition (_._2.dir == INPUT)
  val io = new SimWrapperIO(ins, outs)
  val in_channels: Seq[Channel[Bits]] = ins map { x => 
    val channel = Module(new Channel(x._2)) 
    channel setName ("Channel_" + x._1)
    channel
  }
  val out_channels: Seq[Channel[Bits]] = outs map { x => 
    val channel = Module(new Channel(x._2)) 
    channel setName ("Channel_" + x._1)
    channel
  }
  val fire = Wire(Bool())
  val fireNext = RegNext(fire)

  // Datapath: Channels <> IOs
  for ((in, i) <- io.ins.zipWithIndex) {
    val channel = in_channels(i)
    val buffer = RegEnable(channel.io.out.bits.data, fire)
    ins(i)._2 := Mux(fire, channel.io.out.bits.data, buffer)
    in <> channel.io.in
  }

  for ((out, i) <- io.outs.zipWithIndex) {
    val channel = out_channels(i)
    channel.io.out <> out
    channel.io.in.bits.data := outs(i)._2
  }
  
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
