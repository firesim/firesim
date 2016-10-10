package strober

import chisel3._
import chisel3.util._
import junctions._
import cde.{Parameters, Field}
import midas_widgets._
import dram_midas._

case object SlaveNastiKey extends Field[NastiParameters]
case object ZynqMMIOSize extends Field[BigInt]

object ZynqCtrlSignals extends Enumeration {
  val HOST_RESET, SIM_RESET, STEP, DONE, TRACELEN = Value
}

object ZynqShim {
  def apply[T <: Module](c: =>T)(implicit p: Parameters) = new ZynqShim(new SimWrapper(c))
}

case class ZynqMasterHandlerArgs(
  sim: SimWrapperIO, inNum: Int, outNum: Int, arNum: Int, awNum: Int, rNum: Int, wNum: Int)

class ZynqMasterHandlerIO(args: ZynqMasterHandlerArgs,
                          channelType: => DecoupledIO[UInt])
                         (implicit p: Parameters) extends WidgetIO()(p){

  val ins   = Vec(args.inNum, channelType)
  val outs  = Flipped(Vec(args.outNum, channelType))
  val inT   = Flipped(Vec(args.sim.inT.size, channelType))
  val outT  = Flipped(Vec(args.sim.outT.size, channelType))
  val daisy = Flipped(args.sim.daisy.cloneType)
  val mem   = new Bundle {
    val ar = Vec(args.arNum, channelType)
    val aw = Vec(args.awNum, channelType)
    val r  = Flipped(Vec(args.rNum,  channelType))
    val w  = Vec(args.wNum,  channelType)
  } 
  val ctrlIns  = Vec(ZynqCtrlSignals.values.size, channelType)
  val ctrlOuts = Flipped(Vec(ZynqCtrlSignals.values.size, channelType))
}

class ZynqMasterHandler(args: ZynqMasterHandlerArgs)(implicit p: Parameters) extends Widget()(p) {
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
  val restarts = Wire(Vec(io.daisy.sram.size, ChannelType))
  val inputSeq = (io.ctrlIns ++ io.ins ++ restarts ++ io.mem.ar ++ io.mem.aw ++ io.mem.w).toSeq
  val inputs = Wire(Vec(inputSeq.size, ChannelType))
  io.daisy.sram.zipWithIndex foreach { case (sram, i) =>
    sram.restart := restarts(i).valid
    restarts(i).ready := Bool(true)
  }
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
  val daisyOuts = ChainType.values flatMap (io.daisy(_).toSeq) map (_.out)
  val outputSeq = (io.ctrlOuts ++ io.outs ++ io.inT ++ io.outT ++ daisyOuts ++ io.mem.r).toSeq
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

  // TODO:
  io.daisy.trace foreach (_.in.bits := UInt(0))
  io.daisy.trace foreach (_.in.valid := Bool(false))
  io.daisy.regs foreach (_.in.bits := UInt(0))
  io.daisy.regs foreach (_.in.valid := Bool(false))
  io.daisy.sram foreach (_.in.bits := UInt(0))
  io.daisy.sram foreach (_.in.valid := Bool(false))
  io.daisy.cntr foreach (_.in.bits := UInt(0))
  io.daisy.cntr foreach (_.in.valid := Bool(false))
}

class ZynqShimIO(implicit p: Parameters) extends ParameterizedBundle()(p) {
  val master = Flipped(new WidgetMMIO)
  val slave  = new NastiIO()(p alter Map(NastiKey -> p(SlaveNastiKey)))
}

class ZynqShim[+T <: SimNetwork](c: =>T)(implicit p: Parameters) extends Module with HasWidgets {
  val io = IO(new ZynqShimIO)
  // Simulation Target
  val sim: T = Module(c)
  val simReset = Wire(Bool())
  val hostReset = Wire(Bool())
  val ins = sim.io.inMap.toList.tail filterNot (x => SimMemIO(x._1)) flatMap sim.io.getIns // exclude reset
  val outs = sim.io.outMap.toList filterNot (x => SimMemIO(x._1)) flatMap sim.io.getOuts
  val inBufs = ins.zipWithIndex map { case (in, i) =>
    val q = Module(new Queue(in.bits, 2, flow=true))
    in.bits := q.io.deq.bits
    q.reset := reset || hostReset
    q suggestName s"in_buf_${i}" ; q }
  val outBufs = outs.zipWithIndex map { case (out, i) =>
    val q = Module(new Queue(out.bits, 2, flow=true))
    q.io.enq.bits := out.bits
    q.reset := reset || hostReset
    q suggestName s"out_buf_${i}" ; q }

  // Pass Tokens
  val tickCounter = RegInit(UInt(0))
  val tockCounter = RegInit(UInt(0))
  val tick = (inBufs foldLeft tickCounter.orR)(_ && _.io.deq.valid) &&
             (sim.io.ins foldLeft Bool(true))(_ && _.ready)
  val tock = ((outBufs foldLeft tockCounter.orR)(_ && _.io.enq.ready)) &&
             (sim.io.outs foldLeft Bool(true))(_ && _.valid)
  val idle = !tickCounter.orR && !tockCounter.orR
  sim.reset := reset || hostReset
  sim.io.ins(0).bits  := simReset
  sim.io.ins(0).valid := tick || simReset
  when(tick) { tickCounter := tickCounter - UInt(1) }
  when(tock) { tockCounter := tockCounter - UInt(1) }
  when(simReset) { tickCounter := UInt(0) }
  when(simReset) { tockCounter := UInt(1) }
  ins foreach (_.valid := tick || simReset)
  outs foreach (_.ready := tock)
  inBufs foreach (_.io.deq.ready := tick && tickCounter === UInt(1))
  outBufs foreach (_.io.enq.valid := tock && tockCounter === UInt(1))

  // Host Memory Channels
  val arb = Module(new NastiArbiter(SimMemIO.size+1)(p alter Map(NastiKey -> p(SlaveNastiKey))))
  val mem = arb.io.master(SimMemIO.size)
  private def genChannels(data: Data, prefix: String) = {
    val (ins, outs) = SimUtils.parsePorts(data)
    (ins ++ outs) map {case (w, n) => w -> s"${prefix}_${n}"} flatMap (
      SimUtils.genChannels(_)(p alter Map(EnableSnapshot -> false)))
  }
  val ar = genChannels(mem.ar.bits.addr, "ar")
  val aw = genChannels(mem.aw.bits.addr, "aw")
  val w  = genChannels(mem.w.bits.data,  "w")
  val r  = genChannels(mem.r.bits.data,  "r")

  io.slave <> arb.io.slave

  // Instantiate Master Handler
  import ZynqCtrlSignals._

  val CTRL_NUM = ZynqCtrlSignals.values.size
  val master = addWidget(new ZynqMasterHandler(new ZynqMasterHandlerArgs(
    sim.io, ins.size, outs.size, ar.size, aw.size, r.size, w.size))(
    p alter Map(NastiKey -> p(CtrlNastiKey))), "ZynqMasterHandler")

  master.io.ctrl <> io.master

  hostReset := master.io.ctrlIns(HOST_RESET.id).valid
  master.io.ctrlIns(HOST_RESET.id).ready := Bool(true)
  master.io.ctrlOuts(HOST_RESET.id).valid := Bool(false)

  simReset := master.io.ctrlIns(SIM_RESET.id).valid
  master.io.ctrlIns(SIM_RESET.id).ready := Bool(true)
  master.io.ctrlOuts(SIM_RESET.id).valid := Bool(false)

  when(master.io.ctrlIns(STEP.id).fire()) { 
    tickCounter := master.io.ctrlIns(STEP.id).bits 
    tockCounter := master.io.ctrlIns(STEP.id).bits 
  }
  master.io.ctrlIns(STEP.id).ready := idle
  master.io.ctrlOuts(STEP.id).valid := Bool(false)

  master.io.ctrlOuts(DONE.id).bits := idle
  master.io.ctrlOuts(DONE.id).valid := Bool(true)
  master.io.ctrlIns(DONE.id).ready := Bool(false)

  val tracelen_reg = RegInit(UInt(0, master.nastiXDataBits))
  sim.io.traceLen := tracelen_reg
  when(master.io.ctrlIns(TRACELEN.id).fire()) {
    tracelen_reg := master.io.ctrlIns(TRACELEN.id).bits 
  }
  master.io.ctrlIns(TRACELEN.id).ready := idle
  master.io.ctrlOuts(TRACELEN.id).valid := Bool(false)

  // Target Connection
  implicit val channelWidth = sim.channelWidth
  val IN_ADDRS = SimUtils.genIoMap(sim.io.inputs.tail filterNot (x => SimMemIO(x._1)), CTRL_NUM)
  val OUT_ADDRS = SimUtils.genIoMap(sim.io.outputs filterNot (x => SimMemIO(x._1)), CTRL_NUM)
  (inBufs zip master.io.ins) foreach {case (buf, in) => buf.io.enq <> in}
  (master.io.outs zip outBufs) foreach {case (out, buf) => out <> buf.io.deq}

  val IN_TR_ADDRS = SimUtils.genIoMap(sim.io.inputs, CTRL_NUM + master.io.outs.size)
  val OUT_TR_ADDRS = SimUtils.genIoMap(sim.io.outputs, CTRL_NUM + master.io.outs.size + master.io.inT.size)
  master.io.inT <> sim.io.inT
  master.io.outT <> sim.io.outT

  val SRAM_RESTART_ADDR = CTRL_NUM + master.io.ins.size 
  val DAISY_ADDRS = {
    val offset = CTRL_NUM + master.io.outs.size + master.io.inT.size + master.io.outT.size
    ((ChainType.values foldLeft (Map[ChainType.Value, Int](), offset)){
      case ((map, offset), chainType) => 
        (map + (chainType -> offset), offset + master.io.daisy(chainType).size)
    })._1
  }
  master.io.daisy <> sim.io.daisy
  
  // Memory Connection
  val AR_ADDR = CTRL_NUM + master.io.ins.size + master.restarts.size
  val AW_ADDR = AR_ADDR + master.io.mem.ar.size
  val W_ADDR  = AW_ADDR + master.io.mem.aw.size
  val R_ADDR  = CTRL_NUM + master.io.outs.size +
    master.io.inT.size + master.io.outT.size + master.daisyOuts.size
  (aw zip master.io.mem.aw) foreach {case (buf, io) => buf.io.in <> io}
  (ar zip master.io.mem.ar) foreach {case (buf, io) => buf.io.in <> io}
  (w zip master.io.mem.w) foreach {case (buf, io) => buf.io.in <> io}
  (master.io.mem.r zip r) foreach {case (io, buf) => io <> buf.io.out}

  mem.aw.bits := NastiWriteAddressChannel(UInt(SimMemIO.size), 
    Cat(aw.reverse map (_.io.out.bits)), UInt(log2Up(mem.w.bits.nastiXDataBits/8)))(
    p alter Map(NastiKey -> p(SlaveNastiKey)))
  mem.ar.bits := NastiReadAddressChannel(UInt(SimMemIO.size), 
    Cat(ar.reverse map (_.io.out.bits)), UInt(log2Up(mem.r.bits.nastiXDataBits/8)))(
    p alter Map(NastiKey -> p(SlaveNastiKey)))
  mem.w.bits := NastiWriteDataChannel(Cat(w.reverse map (_.io.out.bits)))(
    p alter Map(NastiKey -> p(SlaveNastiKey)))

  r.zipWithIndex foreach {case (buf, i) =>
    buf.io.in.bits := mem.r.bits.data >> UInt(i*sim.channelWidth)
  }
  mem.aw.valid := (aw foldLeft Bool(true))(_ && _.io.out.valid)
  mem.ar.valid := (ar foldLeft Bool(true))(_ && _.io.out.valid)
  mem.w.valid  := (w  foldLeft Bool(true))(_ && _.io.out.valid)
  mem.r.ready  := (r  foldLeft Bool(true))(_ && _.io.in.ready)
  mem.b.ready  := Bool(true)
  aw foreach (_.io.out.ready := mem.aw.fire())
  ar foreach (_.io.out.ready := mem.ar.fire())
  w  foreach (_.io.out.ready := mem.w.fire())
  r  foreach (_.io.in.valid  := mem.r.fire())
 
  private def targetConnect[T <: Data](arg: (T, T)): Unit = arg match {
    case (target: Bundle, wires: Bundle) => 
      (target.elements.unzip._2 zip wires.elements.unzip._2) foreach targetConnect
    case (target: Vec[_], wires: Vec[_]) => 
      (target.toSeq zip wires.toSeq) foreach targetConnect
    case (target: Bits, wire: Bits) if wire.dir == OUTPUT =>
      target := Cat(sim.io.getOuts(wire).reverse map (_.bits))
    case (target: Bits, wire: Bits) if wire.dir == INPUT => 
      sim.io.getIns(wire).zipWithIndex foreach {case (in, i) => 
        in.bits := target >> UInt(i * sim.io.channelWidth) 
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
          val chunks = sim.io.getOuts(b)
          chunks.foldLeft(Bool(true))(_ && _.valid)
        case INPUT =>
          val chunks = sim.io.getIns(b)
          chunks.foldLeft(Bool(true))(_ && _.ready)
        case _ => throw new RuntimeException("Wire must have a direction")
      }
    }
    // First reduce the chunks for each field; and then the fields themselves
    port.toHost.hValid := outWires map (andReduceChunks(_)) reduce(_ && _)
    port.fromHost.hReady := inWires map (andReduceChunks(_)) reduce(_ && _)
    // Pass the hReady back to the chunks of all target driven fields
    outWires foreach {(outWire: Bits) => {
      val chunks = sim.io.getOuts(outWire)
      chunks foreach (_.ready := port.toHost.hReady)
    }}
    // Pass the hValid back to the chunks for all target sunk fields
    inWires foreach {(inWire: Bits) => {
      val chunks = sim.io.getIns(inWire)
      chunks foreach (_.valid := port.fromHost.hValid)
    }}
  }

  private def channels2Port[T <: Data](port: HostPortIO[T], wires: T): Unit = {
    hostConnect(port, wires)
    targetConnect(port.hBits -> wires)
  }

  (0 until SimMemIO.size) foreach { i =>
    val model = addWidget(
      (p(MemModelKey): @unchecked) match {
        case Some(cfg: BaseConfig) => new MidasMemModel(cfg)(p alter Map(NastiKey -> p(SlaveNastiKey)))
        case None => new SimpleLatencyPipe()(p alter Map(NastiKey -> p(SlaveNastiKey)))},
      s"MemModel_$i")

    arb.io.master(i) <> model.io.host_mem
    model.reset := reset || hostReset

    //Queue HACK: fake two output tokens by connected fromHost.hValid = simReset
    val wires = SimMemIO(i)
    val simResetReg = RegNext(simReset)
    val fakeTNasti = Wire(model.io.tNasti.cloneType)
    model.io.tNasti <> fakeTNasti
    fakeTNasti.fromHost.hValid := model.io.tNasti.fromHost.hValid || simReset || simResetReg
    channels2Port(fakeTNasti, wires)
  }
  genCtrlIO(io.master, p(ZynqMMIOSize))
  StroberCompiler annotate this
}
