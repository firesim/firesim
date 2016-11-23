package midas
package core

import util.ParameterizedBundle // from rocketchip
import chisel3._
import chisel3.util._
import chisel3.compatibility.throwException
import cde.{Parameters, Field}
import junctions.NastiIO
import SimUtils.{parsePorts, getChunks}
import scala.collection.immutable.ListMap
import scala.collection.mutable.{ArrayBuffer, HashSet}

class SimMemIO {
  private val memPorts = ArrayBuffer[NastiIO]()
  private val memWires = HashSet[Bits]()
  def add(mem: NastiIO) {
    val (ins, outs) = SimUtils.parsePorts(mem)
    memWires ++= ins.unzip._1
    memWires ++= outs.unzip._1
    memPorts += mem
  }
  def apply(i: Int): NastiIO = memPorts(i)
  def apply(wire: Bits) = memWires(wire)
  def apply(mem: NastiIO) = memPorts contains mem
  def zipWithIndex = memPorts.toList.zipWithIndex
  def size = memPorts.size
}

object SimUtils {
  def parsePorts(io: Data, reset: Option[Bool] = None, mem: Option[SimMemIO] = None) = {
    val inputs = ArrayBuffer[(Bits, String)]()
    val outputs = ArrayBuffer[(Bits, String)]()
    def loop(name: String, data: Data): Unit = data match {
      case m: NastiIO =>
        m.elements foreach {case (n, e) => loop(s"${name}_${n}", e)}
        mem match {
          case Some(p) if !p(m) => p add m
          case _ =>
        }
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
}

case object TraceMaxLen extends Field[Int]
case object ChannelLen extends Field[Int]
case object ChannelWidth extends Field[Int]
case object SRAMChainNum extends Field[Int]

class TraceQueueIO[T <: Data](data: => T, val entries: Int) extends QueueIO(data, entries) {
  val limit = UInt(INPUT, log2Up(entries))
}

class TraceQueue[T <: Data](data: => T)(implicit p: Parameters) extends Module {
  val io = IO(new TraceQueueIO(data, p(TraceMaxLen)))

  val do_flow = Wire(Bool())
  val do_enq = io.enq.fire() && !do_flow
  val do_deq = io.deq.fire() && !do_flow

  val maybe_full = RegInit(Bool(false))
  val enq_ptr = RegInit(UInt(0, log2Up(io.entries)))
  val deq_ptr = RegInit(UInt(0, log2Up(io.entries)))
  val enq_wrap = enq_ptr === io.limit
  val deq_wrap = deq_ptr === io.limit
  when (do_enq) { enq_ptr := Mux(enq_wrap, UInt(0), enq_ptr + UInt(1)) }
  when (do_deq) { deq_ptr := Mux(deq_wrap, UInt(0), deq_ptr + UInt(1)) }
  when (do_enq =/= do_deq) { maybe_full := do_enq }

  val ptr_match = enq_ptr === deq_ptr
  val empty = ptr_match && !maybe_full
  val full = ptr_match && maybe_full
  val atLeastTwo = full || enq_ptr - deq_ptr >= UInt(2)
  do_flow := empty && io.deq.ready

  val ram = SeqMem(io.entries, data)
  when (do_enq) { ram.write(enq_ptr, io.enq.bits) }

  val ren = io.deq.ready && (atLeastTwo || !io.deq.valid && !empty)
  val raddr = Mux(io.deq.valid, Mux(deq_wrap, UInt(0), deq_ptr + UInt(1)), deq_ptr)
  val ram_out_valid = Reg(next = ren)

  io.deq.valid := Mux(empty, io.enq.valid, ram_out_valid)
  io.enq.ready := !full
  io.deq.bits := Mux(empty, io.enq.bits, ram.read(raddr, ren))
}

class ChannelIO(w: Int)(implicit p: Parameters) extends ParameterizedBundle()(p) {
  val in    = Flipped(Decoupled(UInt(width=w)))
  val out   = Decoupled(UInt(width=w))
  val trace = Decoupled(UInt(width=w))
  val traceLen = UInt(INPUT, log2Up(p(TraceMaxLen)+1))
}

class Channel(val w: Int)(implicit p: Parameters) extends Module {
  val io = IO(new ChannelIO(w))
  val tokens = Module(new Queue(UInt(width=w), p(ChannelLen)))
  tokens.io.enq <> io.in
  io.out <> tokens.io.deq
  if (p(EnableSnapshot)) {
    val trace = Module(new TraceQueue(UInt(width=w)))
    trace suggestName "trace"
    // trace is written when a token is consumed
    trace.io.enq.bits  := io.out.bits
    trace.io.enq.valid := io.out.fire() && trace.io.enq.ready
    trace.io.limit := io.traceLen - UInt(2)
    io.trace <> Queue(trace.io.deq, 1, pipe=true)
    // for debugging
    val trace_count = RegInit(UInt(0, 32))
    trace_count suggestName "trace_count"
    when (io.trace.fire() =/= trace.io.enq.fire()) {
      trace_count := Mux(io.trace.fire(), trace_count - UInt(1), trace_count + UInt(1))
    }
  } else {
    io.trace.valid := Bool(false)
  }
}

trait HasSimWrapperParams {
  implicit val p: Parameters
  implicit val channelWidth = p(ChannelWidth)
  val traceMaxLen = p(TraceMaxLen)
  val daisyWidth = p(DaisyWidth)
  val sramChainNum = p(SRAMChainNum)
  val enableSnapshot = p(EnableSnapshot)
  val enableMemModel = p(EnableMemModel)
}

class SimWrapperIO(io: Data, reset: Bool, mem: Option[SimMemIO])(implicit val p: Parameters) 
    extends ParameterizedBundle()(p) with HasSimWrapperParams {
  val (inputs, outputs) = parsePorts(io, Some(reset), mem)
  val inChannelNum = getChunks(inputs.unzip._1)
  val outChannelNum = getChunks(outputs.unzip._1)

  val ins = Flipped(Vec(inChannelNum, Decoupled(UInt(width=channelWidth))))
  val outs = Vec(outChannelNum, Decoupled(UInt(width=channelWidth)))
  val inT = Vec(if (enableSnapshot) inChannelNum else 0, Decoupled(UInt(width=channelWidth)))
  val outT = Vec(if (enableSnapshot) outChannelNum else 0, Decoupled(UInt(width=channelWidth)))
  val daisy = new DaisyBundle(daisyWidth, sramChainNum)
  val traceLen = UInt(INPUT, log2Up(traceMaxLen + 1))

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

  override def cloneType: this.type =
    new SimWrapperIO(io, reset, None).asInstanceOf[this.type]
}

class TargetBox(targetIo: Data) extends BlackBox {
  val io = IO(new Bundle {
    val clock = Clock(INPUT)
    val reset = Bool(INPUT)
    val io = targetIo.cloneType
  })
}

class SimBox(simIo: SimWrapperIO)
            (implicit val p: Parameters)
             extends BlackBox with HasSimWrapperParams {
  val io = IO(new Bundle {
    val clock = Clock(INPUT)
    val reset = Bool(INPUT)
    val io = simIo.cloneType
  })
  val headerConsts = List(
    "DAISY_WIDTH"   -> daisyWidth,
    "TRACE_MAX_LEN" -> traceMaxLen,
    "CHANNEL_SIZE"  -> log2Up(channelWidth/8)
  )
}

class SimWrapper(targetIo: Data,
                 memIo: SimMemIO)
                (implicit val p: Parameters) extends Module with HasSimWrapperParams {
  val target = Module(new TargetBox(targetIo))
  val io = IO(new SimWrapperIO(target.io.io, target.io.reset, if (enableMemModel) Some(memIo) else None))
  val fire = Wire(Bool())

  def genChannels[T <: Bits](arg: (T, String))(implicit p: cde.Parameters) = {
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
  val cycles = Reg(UInt(width=64))
  when (fire) {
    cycles := Mux(target.io.reset, UInt(0), cycles + UInt(1))
  }
} 
