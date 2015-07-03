package strober

import Chisel._
import scala.collection.mutable.{ArrayBuffer}

object SimWrapper {
  def apply[T <: Module](c: =>T, targetParams: Parameters = Parameters.empty) = {
    val params = targetParams alter SimParams.mask
    Module(new SimWrapper(c))(params)
  }
}

class SimWrapperIO(val t_ins: Array[(String, Bits)], val t_outs: Array[(String, Bits)]) extends Bundle {
  val daisyWidth = params(DaisyWidth)
  def genPacket(arg: (String, Bits)) = {
    val (name, port) = arg
    val packet = Decoupled(new Packet(port))
    if (port.dir == INPUT) packet.flip
    packet nameIt ("io_" + name + "_channel", true)
    packet
  }
  val ins = Vec(t_ins map genPacket)
  val outs = Vec(t_outs map genPacket)
  val daisy = new DaisyBundle(daisyWidth)
}

abstract class SimNetwork extends Module {
  def io: SimWrapperIO 
  def in_channels: Seq[Channel[Bits]]
  def out_channels: Seq[Channel[Bits]]
  val sampleNum = params(SampleNum)
  val traceLen = params(TraceLen)
  val daisyWidth = params(DaisyWidth)
}

class SimWrapper[+T <: Module](c: =>T) extends SimNetwork {
  val target = Module(c)
  val (ins, outs) = target.wires partition (_._2.dir == INPUT)
  val io = new SimWrapperIO(ins, outs)
  val in_channels: Seq[Channel[Bits]] = ins map { x => 
    val channel = Module(new Channel(x._2)) 
    channel.name = "Channel_" + x._1
    channel
  }
  val out_channels: Seq[Channel[Bits]] = outs map { x => 
    val channel = Module(new Channel(x._2)) 
    channel.name = "Channel_" + x._1
    channel
  }
  val fire = Bool()
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
  in_channels foreach { channel =>
    channel.io.out.ready := fire 
  }
   
  // Outputs should be ready after one cycle
  out_channels foreach { channel =>
    channel.io.in.valid := fireNext || RegNext(reset) 
  }

  val stall = target.addPin(Bool(INPUT), "io_stall_t")
  stall := !fire

  transforms.init(this, stall)
}
