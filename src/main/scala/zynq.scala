package strober

import chisel3._
import chisel3.util._
import cde.{Parameters, Field}
import junctions._
import midas_widgets._

case object SlaveNastiKey extends Field[NastiParameters]
case object ZynqMMIOSize extends Field[BigInt]

case class ZynqMasterHandlerArgs(sim: SimWrapperIO, inNum: Int, outNum: Int)

class ZynqMasterHandlerIO(args: ZynqMasterHandlerArgs,
                          channelType: => DecoupledIO[UInt])
                         (implicit p: Parameters) extends WidgetIO()(p){

  val ins  = Vec(args.inNum, channelType)
  val outs = Flipped(Vec(args.outNum, channelType))
  val inT  = Flipped(Vec(args.sim.inT.size, channelType))
  val outT = Flipped(Vec(args.sim.outT.size, channelType))
}

class ZynqMasterHandler(args: ZynqMasterHandlerArgs)(implicit val p: Parameters) extends Widget()(p) 
    with HasNastiParameters {
  def ChannelType = Decoupled(UInt(width=nastiXDataBits))
  val io = IO(new ZynqMasterHandlerIO(args, ChannelType))

  val addrSize       = p(ZynqMMIOSize)/4
  val addrOffsetBits = log2Up(nastiXDataBits/8)
  val addrSizeBits   = log2Up(addrSize) - addrOffsetBits
  override val customSize = Some(addrSize)

  require(p(ChannelWidth) == nastiXDataBits, "Channel width and Nasti data width should be the same")

  /*** INPUTS ***/
  val awId  = Reg(UInt(width=nastiWIdBits))
  val wAddr = Reg(UInt(width=addrSizeBits))
  val wStateIdle :: wStateReady :: wStateWrite :: wStateAck :: Nil = Enum(UInt(), 4)
  val wState = RegInit(wStateIdle)
  val inputSeq = (io.ins).toSeq
  val inputs = Wire(Vec(inputSeq.size, ChannelType))
  (inputSeq zip inputs) foreach { case (x, y) =>
    // TODO: x <> y
    x.bits  := y.bits
    x.valid := y.valid
    y.ready := x.ready
  }
  inputs.zipWithIndex foreach { case (in, i) =>
    in.bits  := io.ctrl.w.bits.data
    in.valid := wAddr === UInt(i) && wState === wStateWrite
  }

  // Write FSM
  switch(wState) {
    is(wStateIdle) {
      when(io.ctrl.aw.valid) {
        wState := wStateReady
        awId   := io.ctrl.aw.bits.id
        wAddr  := io.ctrl.aw.bits.addr >> UInt(addrOffsetBits)
      }
    }
    is(wStateReady) {
      when(io.ctrl.w.valid) {
        wState := wStateWrite
      }
    }
    is(wStateWrite) {
      when(inputs(wAddr).ready) {
        wState := wStateAck
      } 
    }
    is(wStateAck) {
      when(io.ctrl.b.ready) {
        wState := wStateIdle
      }
    }
  }
  //TODO: this is gross; use the library instead
  io.ctrl.aw.ready := wState === wStateIdle
  io.ctrl.w.ready  := wState === wStateWrite
  io.ctrl.b.valid  := wState === wStateAck
  io.ctrl.b.bits   := NastiWriteResponseChannel(awId)

  /*** OUTPUTS ***/
  val arId  = Reg(UInt(width=nastiWIdBits))
  val rAddr = Reg(UInt(width=addrSizeBits))
  val rStateIdle :: rStateRead :: Nil = Enum(UInt(), 2)
  val rState = RegInit(rStateIdle)
  val doRead = rState === rStateRead
  val outputSeq = (io.outs ++ io.inT ++ io.outT).toSeq
  val outputs = Wire(Vec(outputSeq.size, ChannelType))
  outputs zip outputSeq foreach { case (x, y) =>
    // TODO: x <> y
    x.bits := y.bits
    x.valid := y.valid
    y.ready := x.ready
  }
  outputs.zipWithIndex foreach { case (out, i) =>
    out.ready := rAddr === UInt(i) && doRead
  }

  // Read FSM
  switch(rState) {
    is(rStateIdle) {
      when(io.ctrl.ar.valid) {
        rState := rStateRead
        arId   := io.ctrl.ar.bits.id
        rAddr  := io.ctrl.ar.bits.addr >> UInt(addrOffsetBits)
      }
    }
    is(rStateRead) {
      when(io.ctrl.r.ready) {
        rState := rStateIdle
      }
    }
  }
  io.ctrl.ar.ready := rState === rStateIdle
  io.ctrl.r.bits := NastiReadDataChannel(
    id = arId,
    data = outputs(rAddr).bits,
    last = outputs(rAddr).valid && doRead)
  io.ctrl.r.valid := io.ctrl.r.bits.last
}

class ZynqShimIO(implicit p: Parameters) extends ParameterizedBundle()(p) {
  val master = Flipped(new WidgetMMIO()(p alter Map(NastiKey -> p(CtrlNastiKey))))
  val slave  = new NastiIO()(p alter Map(NastiKey -> p(SlaveNastiKey)))
}

class ZynqShim(_simIo: SimWrapperIO, memIo: SimMemIO)(implicit p: Parameters) extends Module with HasWidgets {
  val io = IO(new ZynqShimIO)
  // Simulation Target
  val sim = Module(new SimBox(_simIo))
  val simIo = sim.io.io
  // This reset is used to return the emulation to time 0.
  val simReset = Wire(Bool())

  implicit val channelWidth = sim.channelWidth
  sim.io.clock := clock
  sim.io.reset := reset || simReset

  // Exclude only NastiIO for now
  val pokedIns = simIo.inputs filterNot (x => memIo(x._1))
  val peekedOuts = simIo.outputs filterNot (x => memIo(x._1))
  

  val master = addWidget(new ZynqMasterHandler(new ZynqMasterHandlerArgs(
    simIo, pokedIns.size, peekedOuts.size))(
    p alter Map(NastiKey -> p(CtrlNastiKey))), "ZynqMasterHandler")

  val widgetizedMaster = addWidget(new EmulationMaster, "EmulationMaster")
  simReset := widgetizedMaster.io.simReset

  val inputsWithWidth = pokedIns map { case (wire, name) => (name -> SimUtils.getChunks(wire)) }
  val outputsWithWidth = peekedOuts map { case (wire, name) => (name -> SimUtils.getChunks(wire)) }

  val defaultIOWidget = addWidget(
    new PeekPokeIOWidget(inputsWithWidth, outputsWithWidth),
    "DefaultIOWidget")
  defaultIOWidget.io.step <> widgetizedMaster.io.step
  widgetizedMaster.io.done := defaultIOWidget.io.idle
  defaultIOWidget.reset := reset || simReset


  // Get only the channels driven by the PeekPoke widget
  val inputChannels = pokedIns.unzip._1.flatMap(simIo.getIns(_))
  val outputChannels = peekedOuts.unzip._1.flatMap(simIo.getOuts(_))

  def connectChannels(sinks: Seq[DecoupledIO[UInt]], srcs: Seq[DecoupledIO[UInt]]): Unit =
    sinks.zip(srcs) foreach { case (src, sink) =>  sink <> src }

  connectChannels(inputChannels, defaultIOWidget.io.ins)
  connectChannels(defaultIOWidget.io.outs, outputChannels)

  // Host Memory Channels
  // Masters = Target memory channels + loadMemWidget
  val arb = Module(new NastiArbiter(memIo.size+1)(p alter Map(NastiKey -> p(SlaveNastiKey))))
  val loadMem = addWidget(new LoadMemWidget(SlaveNastiKey), "LOADMEM")
  arb.io.master(memIo.size) <> loadMem.io.toSlaveMem
  io.slave <> arb.io.slave

  if (p(EnableSnapshot)) {
    simIo.traceLen := widgetizedMaster.io.traceLen
  }

  // Target Connection
  val IN_ADDRS = SimUtils.genIoMap(simIo.inputs.tail filterNot (x => memIo(x._1)), 0)
  val OUT_ADDRS = SimUtils.genIoMap(simIo.outputs filterNot (x => memIo(x._1)), 0)

  val IN_TR_ADDRS = SimUtils.genIoMap(simIo.inputs, master.io.outs.size)
  val OUT_TR_ADDRS = SimUtils.genIoMap(simIo.outputs, master.io.outs.size + master.io.inT.size)
  master.io.inT <> simIo.inT
  master.io.outT <> simIo.outT

  if (p(EnableSnapshot)) {
    val daisyController = addWidget(new DaisyController(simIo.daisy), "DaisyChainController")
    daisyController.io.daisy <> simIo.daisy
  }

  private def targetConnect[T <: Data](arg: (T, T)): Unit = arg match {
    case (target: Bundle, wires: Bundle) => 
      (target.elements.unzip._2 zip wires.elements.unzip._2) foreach targetConnect
    case (target: Vec[_], wires: Vec[_]) => 
      (target.toSeq zip wires.toSeq) foreach targetConnect
    case (target: Bits, wire: Bits) if wire.dir == OUTPUT =>
      target := Cat(simIo.getOuts(wire).reverse map (_.bits))
    case (target: Bits, wire: Bits) if wire.dir == INPUT => 
      simIo.getIns(wire).zipWithIndex foreach {case (in, i) =>
        in.bits := target >> UInt(i * simIo.channelWidth)
      }
    case _ =>
  }

  private def hostConnect[T <: Data](port: HostPortIO[T], wires: T): Unit = {
    val (ins, outs) = SimUtils.parsePorts(wires)
    val inWires = ins map (_._1)
    val outWires = outs map(_._1)
    def andReduceChunks(b: Bits): Bool = {
      b.dir match {
        case OUTPUT =>
          val chunks = simIo.getOuts(b)
          chunks.foldLeft(Bool(true))(_ && _.valid)
        case INPUT =>
          val chunks = simIo.getIns(b)
          chunks.foldLeft(Bool(true))(_ && _.ready)
        case _ => throw new RuntimeException("Wire must have a direction")
      }
    }
    // First reduce the chunks for each field; and then the fields themselves
    port.toHost.hValid := outWires map (andReduceChunks(_)) reduce(_ && _)
    port.fromHost.hReady := inWires map (andReduceChunks(_)) reduce(_ && _)
    // Pass the hReady back to the chunks of all target driven fields
    outWires foreach {(outWire: Bits) => {
      val chunks = simIo.getOuts(outWire)
      chunks foreach (_.ready := port.toHost.hReady)
    }}
    // Pass the hValid back to the chunks for all target sunk fields
    inWires foreach {(inWire: Bits) => {
      val chunks = simIo.getIns(inWire)
      chunks foreach (_.valid := port.fromHost.hValid)
    }}
  }

  private def channels2Port[T <: Data](port: HostPortIO[T], wires: T): Unit = {
    hostConnect(port, wires)
    targetConnect(port.hBits -> wires)
  }

  (0 until memIo.size) foreach { i =>
    val model = addWidget(
      (p(MemModelKey): @unchecked) match {
        case Some(modelGen) => modelGen(p alter Map(NastiKey -> p(SlaveNastiKey)))
        case None => new SimpleLatencyPipe()(p alter Map(NastiKey -> p(SlaveNastiKey)))
      }, s"MemModel_$i")

    arb.io.master(i) <> model.io.host_mem
    model.reset := reset || simReset

    //Queue HACK: fake two output tokens by connected fromHost.hValid = simReset
    val wires = memIo(i)
    val simResetReg = RegNext(simReset)
    val fakeTNasti = Wire(model.io.tNasti.cloneType)
    model.io.tNasti <> fakeTNasti
    fakeTNasti.fromHost.hValid := model.io.tNasti.fromHost.hValid || simReset || simResetReg
    channels2Port(fakeTNasti, wires)
  }
  genCtrlIO(io.master, p(ZynqMMIOSize))
}
