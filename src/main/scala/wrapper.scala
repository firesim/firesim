package strober

import Chisel._
import scala.collection.mutable.{ArrayBuffer}

object SimWrapper {
  def apply[T <: Module](c: =>T, targetParams: Parameters = Parameters.empty) = {
    val params = targetParams alter SimParams.mask
    Module(new SimWrapper(c))(params)
  }
}

class SimWrapperIO(target_ins: Array[(String, Bits)], target_outs: Array[(String, Bits)]) extends Bundle {
  def genPacket(arg: (String, Bits)) = {
    val (name, port) = arg
    val packet = Decoupled(new Packet(port))
    if (port.dir == INPUT) packet.flip
    packet nameIt ("io_" + name + "_channel", true)
    packet
  }
  val ins = Vec(target_ins map genPacket)
  val outs = Vec(target_outs map genPacket)
}

abstract class SimNetwork extends Module {
  def io: SimWrapperIO 
  def inMap: Map[Bits, Int]
  def outMap: Map[Bits, Int]
}

class SimWrapper[+T <: Module](c: =>T) extends SimNetwork {
  val target = Module(c)
  val (target_ins, target_outs) = target.wires partition (_._2.dir == INPUT)
  val io = new SimWrapperIO(target_ins, target_outs)
  val inMap = target_ins.unzip._2.zipWithIndex.toMap
  val outMap = target_outs.unzip._2.zipWithIndex.toMap
  val in_channels = target_ins map { x => 
    val channel = Module(new Channel(x._2)) 
    channel.name = "Channel_" + x._1
    channel
  }
  val out_channels = target_outs map { x => 
    val channel = Module(new Channel(x._2)) 
    channel.name = "Channel_" + x._1
    channel
  }

  // Datapath: Channels <> IOs
  for ((in, i) <- io.ins.zipWithIndex) {
    val channel = in_channels(i)
    in <> channel.io.in
    target_ins(i)._2 := channel.io.out.bits.data
  }

  for ((out, i) <- io.outs.zipWithIndex) {
    val channel = out_channels(i)
    channel.io.out <> out
    channel.io.in.bits.data := target_outs(i)._2
  }
  
  // Control
  // Firing condtion:
  // 1) all input values are valid
  // 2) all output FIFOs are not full
  val fire = (in_channels foldLeft Bool(true))(_ && _.io.out.valid) && 
             (out_channels foldLeft Bool(true))(_ && _.io.in.ready)
  val fireNext = RegNext(fire)
 
  // Inputs are consumed when firing conditions are met
  in_channels foreach { channel =>
    channel.io.out.ready := fire
  }
   
  // Outputs should be ready after one cycle
  out_channels foreach { channel =>
     channel.io.in.valid := fireNext
  }

  val stall = target.addPin(Bool(INPUT), "io_stall")
  stall := !fire

  transforms.init(this, stall)
}
