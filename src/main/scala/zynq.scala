package strober

import Chisel._ 
// TODO: import chisel3._
// TODO: import chisel3.util._
import junctions._
import cde.{Parameters, Field}
import midas_widgets._
import dram_midas._

case object SlaveNastiKey  extends Field[NastiParameters]

object ZynqCtrlSignals extends Enumeration {
  val HOST_RESET, SIM_RESET, STEP, DONE, TRACELEN, LATENCY = Value 
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

  val addrOffsetBits = log2Up(nastiXDataBits/8)
  val addrSizeBits   = 12
  override val customSize = Some(BigInt((1 << (addrSizeBits + addrOffsetBits))))

  require(p(ChannelWidth) == nastiXDataBits, "Channel width and Nasti data width should be the same")

  /*** INPUTS ***/
  val awid_r  = Reg(UInt(width=nastiWIdBits))
  val waddr_r = Reg(UInt(width=addrSizeBits))
  val st_wr_idle :: st_aw_done :: st_wr_write :: st_wr_ack :: Nil = Enum(UInt(), 4)
  val st_wr = RegInit(st_wr_idle)
  val do_write = st_wr === st_wr_write
  val restarts = Wire(Vec(io.daisy.sram.size, ChannelType))
  val inputSeq = (io.ctrlIns ++ io.ins ++ restarts ++ io.mem.ar ++ io.mem.aw ++ io.mem.w).toSeq
  val inputs = Wire(Vec(inputSeq.size, ChannelType))
  (inputSeq zip inputs) foreach { case (x, y) => x <> y }
  inputs.zipWithIndex foreach { case (in, i) =>
    in.bits  := io.ctrl.w.bits.data
    in.valid := waddr_r === UInt(i) && do_write
  }

  // Write FSM
  switch(st_wr) {
    is(st_wr_idle) {
      when(io.ctrl.aw.valid) {
        st_wr   := st_aw_done
        awid_r  := io.ctrl.aw.bits.id
        waddr_r := io.ctrl.aw.bits.addr >> UInt(addrOffsetBits)
      }
    }
    is(st_aw_done) {
      when(io.ctrl.w.valid) {
        st_wr := st_wr_write
      }
    }
    is(st_wr_write) {
      when(inputs(waddr_r).ready) {
        st_wr := st_wr_ack
      } 
    }
    is(st_wr_ack) {
      when(io.ctrl.b.ready) {
        st_wr := st_wr_idle
      }
    }
  }
  //TODO: this is gross; use the library instead
  io.ctrl.aw.ready := st_wr === st_wr_idle
  io.ctrl.w.ready  := do_write
  io.ctrl.b.valid  := st_wr === st_wr_ack
  io.ctrl.b.bits   := NastiWriteResponseChannel(awid_r)

  /*** OUTPUTS ***/
  val arid_r  = Reg(UInt(width=nastiWIdBits))
  val raddr_r = Reg(UInt(width=addrSizeBits))
  val st_rd_idle :: st_rd_read :: Nil = Enum(UInt(), 2)
  val st_rd = RegInit(st_rd_idle)
  val do_read = st_rd === st_rd_read
  val daisyOuts = ChainType.values flatMap (io.daisy(_).toSeq) map (_.out)
  val outputSeq = (io.ctrlOuts ++ io.outs ++ io.inT ++ io.outT ++ daisyOuts ++ io.mem.r).toSeq
  val outputs = Wire(Vec(outputSeq.size, ChannelType))
  outputs zip outputSeq foreach { case (x, y) => x <> y }
  outputs.zipWithIndex foreach { case (out, i) =>
    out.ready := raddr_r === UInt(i) && do_read
  }

  // Read FSM
  switch(st_rd) {
    is(st_rd_idle) {
      when(io.ctrl.ar.valid) {
        st_rd   := st_rd_read
        arid_r  := io.ctrl.ar.bits.id
        raddr_r := io.ctrl.ar.bits.addr >> UInt(addrOffsetBits)
      }
    }
    is(st_rd_read) {
      when(io.ctrl.r.ready) {
        st_rd   := st_rd_idle
      }
    }
  }
  io.ctrl.ar.ready := st_rd === st_rd_idle
  io.ctrl.r.bits := NastiReadDataChannel(
    id = arid_r,
    data = outputs(raddr_r).bits,
    last = outputs(raddr_r).valid && do_read)
  io.ctrl.r.valid := io.ctrl.r.bits.last

  // TODO:
  io.daisy.regs foreach (_.in.bits := UInt(0))
  io.daisy.regs foreach (_.in.valid := Bool(false))
  io.daisy.trace foreach (_.in.bits := UInt(0))
  io.daisy.trace foreach (_.in.valid := Bool(false))
  io.daisy.cntr foreach (_.in.bits := UInt(0))
  io.daisy.cntr foreach (_.in.valid := Bool(false))
  io.daisy.sram.zipWithIndex foreach {case (sram, i) =>
    sram.in.bits := UInt(0)
    sram.in.valid := Bool(false)
    sram.restart := restarts(i).valid
    restarts(i).ready := Bool(true)
  }
}

class ZynqShimIO(implicit p: Parameters) extends ParameterizedBundle()(p) {
  val master = Flipped(new WidgetMMIO)
  val slave  = new NastiIO()(p alter Map(NastiKey -> p(SlaveNastiKey)))
}

class ZynqShim[+T <: SimNetwork](c: =>T)(implicit p: Parameters) extends Module 
    with HasWidgets{
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
      SimUtils.genChannels(_)(p, false))
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

  val latency_reg = RegInit(UInt(0, master.nastiXDataBits))
  when(master.io.ctrlIns(LATENCY.id).fire()) {
    latency_reg := master.io.ctrlIns(LATENCY.id).bits
  }
  master.io.ctrlIns(LATENCY.id).ready := idle
  master.io.ctrlOuts(LATENCY.id).valid := Bool(false)

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
    Vec(aw map (_.io.out.bits)).toBits, UInt(log2Up(mem.w.bits.nastiXDataBits/8)))(
    p alter Map(NastiKey -> p(SlaveNastiKey)))
  mem.ar.bits := NastiReadAddressChannel(UInt(SimMemIO.size), 
    Vec(ar map (_.io.out.bits)).toBits, UInt(log2Up(mem.r.bits.nastiXDataBits/8)))(
    p alter Map(NastiKey -> p(SlaveNastiKey)))
  mem.w.bits := NastiWriteDataChannel(Vec(w map (_.io.out.bits)).toBits)(
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
       target := Cat(sim.io.getOuts(wire).map(_.bits))
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
      p(MemModelKey) match {
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
  genCtrlIO(io.master)
  StroberCompiler annotate this
}
