package strober

import Chisel._
import cde.{Parameters, Field}
import junctions._

trait NastiSite
case object NastiMaster extends NastiSite
case object NastiSlave extends NastiSite
case object NastiType extends Field[NastiSite]
case object CacheBlockSize extends Field[Int]

object NastiShim {
  def apply[T <: Module](c: =>T)(implicit p: Parameters) = Module(new NastiShim(new SimWrapper(c)))
}

class NastiMasterHandler(simIo: SimWrapperIO, memIo: NastiIO)(implicit p: Parameters) extends NastiModule()(p) {
  val io = new Bundle {
    val nasti = (new NastiIO).flip
    val fin = Decoupled(UInt(width=nastiXDataBits)).flip
    val step = Decoupled(UInt(width=nastiXDataBits))
    val tracelen = Decoupled(UInt(width=nastiXDataBits))
    val latency = Decoupled(UInt(width=nastiXDataBits))
    val ins = Vec(simIo.inMap filterNot (SimMemIO contains _._1) flatMap simIo.getIns map (_.cloneType))
    val outs = Vec(simIo.outMap filterNot (SimMemIO contains _._1) flatMap simIo.getOuts map (_.cloneType.flip))
    val inT = Vec(simIo.inMap  flatMap simIo.getIns  map (_.cloneType.flip))
    val outT = Vec(simIo.outMap flatMap simIo.getOuts map (_.cloneType.flip))
    val daisy = simIo.daisy.cloneType.flip
    val reset_t = Bool(OUTPUT)
    val mem = new Bundle {
      val ar = Vec(memIo.ar.bits.addr.cloneType.asOutput.flatten map
        ("mem_ar" -> _._2) flatMap simIo.genPacket)
      val aw = Vec(memIo.aw.bits.addr.cloneType.asOutput.flatten map
        ("mem_aw" -> _._2) flatMap simIo.genPacket)
      val w  = Vec(memIo.w.bits.data.cloneType.asOutput.flatten map
        ("mem_w" -> _._2) flatMap simIo.genPacket)
      val r  = Vec(memIo.r.bits.data.cloneType.asInput.flatten map
        ("mem_r" -> _._2) flatMap simIo.genPacket)
    }
  }
  val addrOffsetBits   = log2Up(nastiXDataBits/8)
  val addrSizeBits     = 10
  val resetAddr        = (1 << addrSizeBits) - 1
  val sram0RestartAddr = (1 << addrSizeBits) - 2
  val sram1RestartAddr = (1 << addrSizeBits) - 3

  require(p(ChannelWidth) == nastiXDataBits, "Channel width and Nasti data width should be the same")

  /*** INPUTS ***/
  val waddr_r = Reg(UInt(width=addrSizeBits))
  val awid_r  = Reg(UInt(width=nastiWIdBits))
  val st_wr_idle :: st_wr_write :: st_wr_ack :: Nil = Enum(UInt(), 3)
  val st_wr = RegInit(st_wr_idle)
  val do_write = st_wr === st_wr_write
  val ins = Seq(io.step) ++ io.ins ++ Seq(io.tracelen, io.latency) ++ io.mem.ar ++ io.mem.aw ++ io.mem.w
  ins.zipWithIndex foreach {case (in, i) =>
    val off = UInt(i)
    in.bits  := io.nasti.w.bits.data
    in.valid := waddr_r === off && do_write
  }
  io.reset_t               := do_write && (waddr_r === UInt(resetAddr))
  io.daisy.sram(0).restart := do_write && (waddr_r === UInt(sram0RestartAddr))
  io.daisy.sram(1).restart := do_write && (waddr_r === UInt(sram1RestartAddr))

  // Write FSM
  switch(st_wr) {
    is(st_wr_idle) {
      when(io.nasti.aw.valid && io.nasti.w.valid) {
        st_wr   := st_wr_write
        waddr_r := io.nasti.aw.bits.addr >> UInt(addrOffsetBits)
        awid_r  := io.nasti.aw.bits.id
      }
    }
    is(st_wr_write) {
      when(Vec(ins map (_.ready))(waddr_r) || waddr_r === UInt(resetAddr) || 
          waddr_r === UInt(sram0RestartAddr) || waddr_r === UInt(sram1RestartAddr)) {
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
  val raddr_r = Reg(UInt(width=addrSizeBits))
  val arid_r  = Reg(UInt(width=nastiWIdBits))
  val st_rd_idle :: st_rd_read :: Nil = Enum(UInt(), 2)
  val st_rd = RegInit(st_rd_idle)
  val do_read = st_rd === st_rd_read

  val snapOuts = ChainType.values.toList map (t => io.daisy(t).out)
  val outs = Seq(io.fin) ++ io.outs ++ io.inT ++ io.outT ++ snapOuts ++ io.mem.r

  outs.zipWithIndex foreach {case (out, i) =>
    val off = UInt(i)
    out.ready := raddr_r === off && do_read
  }

  // Read FSM
  switch(st_rd) {
    is(st_rd_idle) {
      when(io.nasti.ar.valid) {
        st_rd   := st_rd_read
        raddr_r := io.nasti.ar.bits.addr >> UInt(addrOffsetBits)
        arid_r  := io.nasti.ar.bits.id
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
    data = Vec(outs map (_.bits))(raddr_r), 
    last = Vec(outs map (_.valid))(raddr_r) && do_read)
  io.nasti.r.valid := io.nasti.r.bits.last
  
  // TODO:
  io.daisy.regs.in.bits := UInt(0)
  io.daisy.regs.in.valid := Bool(false)
  io.daisy.trace.in.bits := UInt(0)
  io.daisy.trace.in.valid := Bool(false)
  io.daisy.sram(0).in.bits := UInt(0)
  io.daisy.sram(0).in.valid := Bool(false)
  io.daisy.sram(1).in.bits := UInt(0)
  io.daisy.sram(1).in.valid := Bool(false)

  // address assignmnet
  // 0 : step
  val inMap = simIo.genIoMap(simIo.t_ins filterNot (SimMemIO contains _._2)) map {
    case (wire, id) => wire -> (1 + id) }
  val traceLenAddr = io.ins.size + 1
  val memCycleAddr = traceLenAddr + 1

  // 0 : fin
  val outMap = simIo.genIoMap(simIo.t_outs filterNot (SimMemIO contains _._2)) map {
    case (wire, id) => wire -> (1 + id) }
  val inTrMap  = simIo.inMap  map {
    case (wire, id) => wire -> (1 + io.outs.size + id)}
  val outTrMap = simIo.outMap map {
    case (wire, id) => wire -> (1 + io.outs.size + io.inT.size + id)}
  val snapOutMap = (ChainType.values.toList map (t => 
    t -> (1 + t.id + io.outs.size + io.inT.size + io.outT.size))).toMap

  val memMap = ((simIo.genIoMap(memIo.ar.bits.addr.flatten ++
    memIo.aw.bits.addr.flatten ++ memIo.w.bits.data.flatten) map {
      case (wire, id) => wire -> (id + 1 + memCycleAddr)}) ++
    (simIo.genIoMap(memIo.r.bits.data.flatten) map {
      case (wire, id) => wire -> (id + 1 + io.outs.size + 
        io.inT.size + io.outT.size + ChainType.values.size)})).toMap
}

class NastiShimIO(implicit p: Parameters) extends ParameterizedBundle()(p) {
  val master = (new NastiIO()(p alter Map(NastiType -> NastiMaster))).flip
  val slave  =  new NastiIO()(p alter Map(NastiType -> NastiSlave))
}

class NastiShim[+T <: SimNetwork](c: =>T)(implicit p: Parameters) extends NastiModule()(p) {
  val io = new NastiShimIO
  // Simulation Target
  val sim: T = Module(c)
  val ins  = Vec(sim.io.inMap  filterNot (SimMemIO contains _._1) flatMap sim.io.getIns)
  val outs = Vec(sim.io.outMap filterNot (SimMemIO contains _._1) flatMap sim.io.getOuts)
  val in_bufs  = ins map { in => 
    val q = Module(new Queue(in.bits, 2, flow=true))
    in.bits := q.io.deq.bits ; q } 
  val out_bufs = outs.toList map { out =>
    val q = Module(new Queue(out.bits, 2, flow=true))
    q.io.enq.bits := out.bits ; q }
  in_bufs.zipWithIndex  map {case (buf, i) => buf setName (s"in_buf_${i}")}
  out_bufs.zipWithIndex map {case (buf, i) => buf setName (s"out_buf_${i}")}

  val arb    = Module(new NastiArbiter(SimMemIO.size+1)(p alter Map(NastiType -> NastiSlave)))
  val mem    = arb.io.master(SimMemIO.size)
  val master = Module(new NastiMasterHandler(sim.io, mem)(p alter Map(NastiType -> NastiMaster)))
  val ar = mem.ar.bits.addr.cloneType.asOutput.flatten map 
    ("ar" -> _._2) flatMap (sim.genChannels(_)(this, false))
  val aw = mem.aw.bits.addr.cloneType.asOutput.flatten map 
    ("aw" -> _._2) flatMap (sim.genChannels(_)(this, false))
  val w  = mem.w.bits.data.cloneType.asOutput.flatten map
    ("w" -> _._2) flatMap (sim.genChannels(_)(this, false))
  val r  = mem.r.bits.data.cloneType.asInput.flatten map 
    ("r" -> _._2) flatMap (sim.genChannels(_)(this, false))

  // Pass tokens
  val tickCounter = RegInit(UInt(0))
  val tockCounter = RegInit(UInt(0))
  val tick = (in_bufs foldLeft tickCounter.orR)(_ && _.io.deq.valid) &&
             (sim.io.ins foldLeft Bool(true))(_ && _.ready)
  val tock = ((out_bufs foldLeft Bool(true))(_ && _.io.enq.ready) || tockCounter.orR) &&
             (sim.io.outs foldLeft Bool(true))(_ && _.valid)
 
  when(tick) { tickCounter := tickCounter - UInt(1) }
  when(tock) { tockCounter := tockCounter - UInt(1) }
  ins  foreach (_.valid := tick)
  outs foreach (_.ready := tock)
  in_bufs  foreach (_.io.deq.ready := tick && tickCounter === UInt(1))
  out_bufs foreach (_.io.enq.valid := tock && tockCounter === UInt(1))

  // Master Connection
  master.io.nasti <> io.master
  master.io.step.ready := !tickCounter.orR && !tockCounter.orR
  when(master.io.step.fire()) { 
    tickCounter := master.io.step.bits 
    tockCounter := master.io.step.bits 
  }.elsewhen(master.io.reset_t) {
    tockCounter := UInt(1)
  }
  sim.reset := master.io.reset_t
  master.io.fin.valid := Bool(true)
  master.io.fin.bits  := !tockCounter.orR
  master.io.tracelen  <> sim.io.traceLen
  master.io.ins  <> Vec(in_bufs  map (_.io.enq))
  master.io.outs <> Vec(out_bufs map (_.io.deq)) 
  master.io.inT  <> sim.io.inT
  master.io.outT <> sim.io.outT
  master.io.mem.aw <> Vec(aw map (_.io.in))
  master.io.mem.ar <> Vec(ar map (_.io.in))
  master.io.mem.w  <> Vec(w  map (_.io.in))
  master.io.mem.r  <> Vec(r  map (_.io.out))
  master.io.daisy.trace.in <> sim.io.daisy.trace.in
  master.io.daisy.regs.in  <> sim.io.daisy.regs.in
  master.io.daisy.sram(0).in <> sim.io.daisy.sram(0).in
  master.io.daisy.sram(1).in <> sim.io.daisy.sram(1).in
  master.io.daisy.cntr.in  <> sim.io.daisy.cntr.in
  // bulk connection not working due to empty outputs for now
  master.io.daisy.trace.out.bits  := sim.io.daisy.trace.out.bits
  master.io.daisy.trace.out.valid := sim.io.daisy.trace.out.valid
  sim.io.daisy.trace.out.ready := master.io.daisy.trace.out.ready
  master.io.daisy.regs.out.bits  := sim.io.daisy.regs.out.bits
  master.io.daisy.regs.out.valid := sim.io.daisy.regs.out.valid
  sim.io.daisy.regs.out.ready := master.io.daisy.regs.out.ready
  master.io.daisy.sram(0).out.bits  := sim.io.daisy.sram(0).out.bits
  master.io.daisy.sram(0).out.valid := sim.io.daisy.sram(0).out.valid
  sim.io.daisy.sram(0).out.ready := master.io.daisy.sram(0).out.ready
  sim.io.daisy.sram(0).restart := master.io.daisy.sram(0).restart
  master.io.daisy.sram(1).out.bits  := sim.io.daisy.sram(1).out.bits
  master.io.daisy.sram(1).out.valid := sim.io.daisy.sram(1).out.valid
  sim.io.daisy.sram(1).out.ready := master.io.daisy.sram(1).out.ready
  sim.io.daisy.sram(1).restart := master.io.daisy.sram(1).restart
  master.io.daisy.cntr.out.bits  := sim.io.daisy.cntr.out.bits
  master.io.daisy.cntr.out.valid := sim.io.daisy.cntr.out.valid
  sim.io.daisy.cntr.out.ready := master.io.daisy.cntr.out.ready

  // Slave Connection
  io.slave <> arb.io.slave

  // Memory Connection
  for (i <-0 until SimMemIO.size) {
    val conv = Module(new ChannelMemIOConverter)
    conv setName s"mem_conv_${i}"
    conv.reset := master.io.reset_t
    conv.io.latency  <> master.io.latency
    conv.io.sim_mem  <> (SimMemIO(i), sim.io)
    conv.io.host_mem <> arb.io.master(i)
  }

  mem.aw.bits := NastiWriteAddressChannel(UInt(SimMemIO.size), 
    Vec(aw map (_.io.out.bits)).toBits, UInt(log2Up(mem.w.bits.nastiXDataBits/8)))
  mem.ar.bits := NastiReadAddressChannel(UInt(SimMemIO.size), 
    Vec(ar map (_.io.out.bits)).toBits, UInt(log2Up(mem.r.bits.nastiXDataBits/8)))
  mem.w.bits := NastiWriteDataChannel(Vec(w map (_.io.out.bits)).toBits)
  r.zipWithIndex foreach {case (buf, i) =>
    buf.io.in.bits := mem.r.bits.data >> UInt(i*sim.channelWidth)}
  mem.aw.valid := (aw foldLeft Bool(true))(_ && _.io.out.valid)
  mem.ar.valid := (ar foldLeft Bool(true))(_ && _.io.out.valid)
  mem.w.valid := (w foldLeft Bool(true))(_ && _.io.out.valid)
  mem.r.ready := (w foldLeft Bool(true))(_ && _.io.in.ready)
  aw foreach (_.io.out.ready := mem.aw.ready)
  ar foreach (_.io.out.ready := mem.ar.ready)
  w foreach (_.io.out.ready := mem.w.ready)
  r foreach (_.io.in.valid := mem.r.valid)
  mem.b.ready := Bool(true)

  transforms.init(this)
}
