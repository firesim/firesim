package strober

import Chisel._
import junctions._
import cde.{Parameters, Field}

case object MasterNastiKey extends Field[NastiParameters]
case object SlaveNastiKey  extends Field[NastiParameters]

object ZynqShim {
  def apply[T <: Module](c: =>T)(implicit p: Parameters) = new ZynqShim(new SimWrapper(c))
}

case class ZynqMasterHandlerArgs(
  sim: SimWrapperIO, inNum: Int, outNum: Int, 
  arNum: Int, awNum: Int, rNum: Int, wNum: Int, ctrlNum: Int)
class ZynqMasterHandler(args: ZynqMasterHandlerArgs)(implicit p: Parameters) extends NastiModule()(p) {
  def ChannelType = Decoupled(UInt(width=nastiXDataBits))
  val io = new Bundle {
    val nasti = (new NastiIO).flip
    val ins   = Vec(args.inNum, ChannelType)
    val outs  = Vec(args.outNum, ChannelType).flip
    val inT   = Vec(args.sim.inT.size, ChannelType).flip
    val outT  = Vec(args.sim.outT.size, ChannelType).flip
    val daisy = args.sim.daisy.cloneType.flip
    val mem   = new Bundle {
      val ar = Vec(args.arNum, ChannelType)
      val aw = Vec(args.awNum, ChannelType)
      val r  = Vec(args.rNum,  ChannelType).flip
      val w  = Vec(args.wNum,  ChannelType)
    } 
    val ctrlIns  = Vec(args.ctrlNum, ChannelType)
    val ctrlOuts = Vec(args.ctrlNum, ChannelType).flip
  }

  val addrOffsetBits = log2Up(nastiXDataBits/8)
  val addrSizeBits   = 10

  require(p(ChannelWidth) == nastiXDataBits, "Channel width and Nasti data width should be the same")

  /*** INPUTS ***/
  val awid_r  = Reg(UInt(width=nastiWIdBits))
  val waddr_r = Reg(UInt(width=addrSizeBits))
  val st_wr_idle :: st_wr_write :: st_wr_ack :: Nil = Enum(UInt(), 3)
  val st_wr = RegInit(st_wr_idle)
  val do_write = st_wr === st_wr_write
  val restarts = Wire(Vec(io.daisy.sram.size, ChannelType))
  val inputSeq = (io.ctrlIns ++ io.ins ++ restarts ++ io.mem.ar ++ io.mem.aw ++ io.mem.w).toSeq
  val inputs = Wire(Vec(inputSeq.size, ChannelType))
  (inputSeq zip inputs) foreach {case (x, y) => x <> y}
  inputs.zipWithIndex foreach {case (in, i) =>
    in.bits  := io.nasti.w.bits.data
    in.valid := waddr_r === UInt(i) && do_write
  }

  // Write FSM
  switch(st_wr) {
    is(st_wr_idle) {
      when(io.nasti.aw.valid && io.nasti.w.valid) {
        st_wr   := st_wr_write
        awid_r  := io.nasti.aw.bits.id
        waddr_r := io.nasti.aw.bits.addr >> UInt(addrOffsetBits)
      }
    }
    is(st_wr_write) {
      when(inputs(waddr_r).ready) {
        st_wr := st_wr_ack
      } 
    }
    is(st_wr_ack) {
      when(io.nasti.b.ready) {
        st_wr := st_wr_idle
      }
    }
  }
  io.nasti.aw.ready := do_write
  io.nasti.w.ready  := do_write
  io.nasti.b.valid  := st_wr === st_wr_ack
  io.nasti.b.bits   := NastiWriteResponseChannel(awid_r)

  /*** OUTPUTS ***/
  val arid_r  = Reg(UInt(width=nastiWIdBits))
  val raddr_r = Reg(UInt(width=addrSizeBits))
  val st_rd_idle :: st_rd_read :: Nil = Enum(UInt(), 2)
  val st_rd = RegInit(st_rd_idle)
  val do_read = st_rd === st_rd_read
  val daisyOuts = ChainType.values flatMap (io.daisy(_).toSeq) map (_.out)
  val outputSeq = (io.ctrlOuts ++ io.outs ++ io.inT ++ io.outT ++ daisyOuts ++ io.mem.r).toSeq
  val outputs = Wire(Vec(outputSeq.size, ChannelType))
  outputs zip outputSeq foreach {case (x, y) => x <> y}
  outputs.zipWithIndex foreach {case (out, i) => out.ready := raddr_r === UInt(i) && do_read}

  // Read FSM
  switch(st_rd) {
    is(st_rd_idle) {
      when(io.nasti.ar.valid) {
        st_rd   := st_rd_read
        arid_r  := io.nasti.ar.bits.id
        raddr_r := io.nasti.ar.bits.addr >> UInt(addrOffsetBits)
      }
    }
    is(st_rd_read) {
      when(io.nasti.r.ready) {
        st_rd   := st_rd_idle
      }
    }
  }
  io.nasti.ar.ready := st_rd === st_rd_idle
  io.nasti.r.bits := NastiReadDataChannel(
    id = arid_r,
    data = outputs(raddr_r).bits,
    last = outputs(raddr_r).valid && do_read)
  io.nasti.r.valid := io.nasti.r.bits.last

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
  val master = (new NastiIO()(p alter Map(NastiKey -> p(MasterNastiKey)))).flip
  val slave  =  new NastiIO()(p alter Map(NastiKey -> p(SlaveNastiKey)))
}

class ZynqShim[+T <: SimNetwork](c: =>T)(implicit p: Parameters) extends Module {
  val io = new ZynqShimIO
  // Simulation Target
  val sim: T = Module(c)
  val simReset = Wire(Bool())
  val simResetNext = RegNext(simReset)
  val ins = sim.io.inMap.toList.tail filterNot (x => SimMemIO(x._1)) flatMap sim.io.getIns // exclude reset
  val outs = sim.io.outMap.toList filterNot (x => SimMemIO(x._1)) flatMap sim.io.getOuts
  val inBufs = ins.zipWithIndex map { case (in, i) =>
    val q = Module(new Queue(in.bits, 2, flow=true))
    in.bits := q.io.deq.bits
    q.reset := reset || simReset
    q suggestName s"in_buf_${i}" ; q }
  val outBufs = outs.zipWithIndex map { case (out, i) =>
    val q = Module(new Queue(out.bits, 2, flow=true))
    q.io.enq.bits := out.bits
    q.reset := reset || simReset
    q suggestName s"out_buf_${i}" ; q }

  // Pass Tokens
  val tickCounter = RegInit(UInt(0))
  val tockCounter = RegInit(UInt(0))
  val tick = (inBufs foldLeft tickCounter.orR)(_ && _.io.deq.valid) &&
             (sim.io.ins foldLeft Bool(true))(_ && _.ready)
  val tock = ((outBufs foldLeft tockCounter.orR)(_ && _.io.enq.ready)) &&
             (sim.io.outs foldLeft Bool(true))(_ && _.valid)
  val idle = !tickCounter.orR && !tockCounter.orR
  sim.reset := reset || simReset
  sim.io.ins(0).bits  := simResetNext
  sim.io.ins(0).valid := tick || simResetNext
  when(tick) { tickCounter := tickCounter - UInt(1) }
  when(tock) { tockCounter := tockCounter - UInt(1) }
  when(simReset) { tickCounter := UInt(0) }
  when(simReset) { tockCounter := UInt(1) }
  ins foreach (_.valid := tick || simResetNext)
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
  val CTRL_NUM = 5
  val master = Module(new ZynqMasterHandler(new ZynqMasterHandlerArgs(
    sim.io, ins.size, outs.size, ar.size, aw.size, r.size, w.size, CTRL_NUM))(
    p alter Map(NastiKey -> p(MasterNastiKey))))

  master.io.nasti <> io.master

  // 0: reset
  val RESET_ADDR = 0
  simReset := master.io.ctrlIns(RESET_ADDR).valid
  master.io.ctrlIns(RESET_ADDR).ready := Bool(true)
  master.io.ctrlOuts(RESET_ADDR).valid := Bool(false)
  
  // 1: steps
  val STEP_ADDR = 1
  when(master.io.ctrlIns(STEP_ADDR).fire()) { 
    tickCounter := master.io.ctrlIns(STEP_ADDR).bits 
    tockCounter := master.io.ctrlIns(STEP_ADDR).bits 
  }
  master.io.ctrlIns(STEP_ADDR).ready := idle
  master.io.ctrlOuts(STEP_ADDR).valid := Bool(false)

  // 2: done
  val DONE_ADDR = 2
  master.io.ctrlOuts(DONE_ADDR).bits := idle
  master.io.ctrlOuts(DONE_ADDR).valid := Bool(true)
  master.io.ctrlIns(DONE_ADDR).ready := Bool(false)

  // 3: traceLen
  val TRACELEN_ADDR = 3
  val tracelen_reg = RegInit(UInt(0, master.nastiXDataBits))
  sim.io.traceLen := tracelen_reg
  when(master.io.ctrlIns(TRACELEN_ADDR).fire()) {
    tracelen_reg := master.io.ctrlIns(TRACELEN_ADDR).bits 
  }
  master.io.ctrlIns(TRACELEN_ADDR).ready := idle
  master.io.ctrlOuts(TRACELEN_ADDR).valid := Bool(false)

  // 4: latency
  val LATENCY_ADDR = 4
  val latency_reg = RegInit(UInt(0, master.nastiXDataBits))
  when(master.io.ctrlIns(LATENCY_ADDR).fire()) {
    latency_reg := master.io.ctrlIns(LATENCY_ADDR).bits
  }
  master.io.ctrlIns(LATENCY_ADDR).ready := idle
  master.io.ctrlOuts(LATENCY_ADDR).valid := Bool(false)

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
      target := Vec(sim.io.getOuts(wire) map (_.bits)).toBits
    case (target: Bits, wire: Bits) if wire.dir == INPUT => 
      sim.io.getIns(wire).zipWithIndex foreach {case (in, i) => 
        in.bits := target >> UInt(i * sim.io.channelWidth) 
      }
    case _ =>
  }

  private def hostConnect[T <: Data](valid: Bool, wires: T, ready: Bool): Bool = wires match {
    case b: Bundle =>
      (b.elements.unzip._2 foldLeft valid)(hostConnect(_, _, ready))
    case v: Vec[_] =>
      (v.toSeq foldLeft valid)(hostConnect(_, _, ready))
    case b: Bits if b.dir == OUTPUT =>
      val outs = sim.io.getOuts(b)
      outs foreach (_.ready := ready)
      (outs foldLeft valid)(_ && _.valid)
    case b: Bits if b.dir == INPUT =>
      val ins = sim.io.getIns(b)
      ins foreach (_.valid := ready || simResetNext)
      (ins foldLeft valid)(_ && _.ready)
  }

  (0 until SimMemIO.size) foreach { i =>
    val model = Module(new MemModel()(p alter Map(NastiKey -> p(SlaveNastiKey))))
    val wires = SimMemIO(i)
    model.reset := reset || simReset
    model suggestName s"MemModel_$i"
    model.io.latency := latency_reg
    arb.io.master(i) <> model.io.host_mem
    targetConnect(model.io.sim_mem.aw.target -> wires.aw)
    targetConnect(model.io.sim_mem.ar.target -> wires.ar)
    targetConnect(model.io.sim_mem.r.target  -> wires.r)
    targetConnect(model.io.sim_mem.w.target  -> wires.w)
    targetConnect(model.io.sim_mem.b.target  -> wires.b)
    model.io.sim_mem.aw.valid := hostConnect(Bool(true), wires.aw, model.io.sim_mem.aw.ready)
    model.io.sim_mem.ar.valid := hostConnect(Bool(true), wires.ar, model.io.sim_mem.ar.ready)
    model.io.sim_mem.w.valid  := hostConnect(Bool(true), wires.w,  model.io.sim_mem.w.ready)
    model.io.sim_mem.r.ready  := hostConnect(Bool(true), wires.r,  model.io.sim_mem.r.valid)
    model.io.sim_mem.b.ready  := hostConnect(Bool(true), wires.b,  model.io.sim_mem.b.valid)
  }
}
