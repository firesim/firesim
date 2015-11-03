package strober

import Chisel._
import junctions._

case object NASTIName extends Field[String]
case object NASTIAddrSizeBits extends Field[Int]
case object MemBlockBytes extends Field[Int]
case object MemAddrSizeBits extends Field[Int]

object NASTIShim {
  def apply[T <: Module](c: =>T)(params: Parameters) = Module(new NASTIShim(new SimWrapper(c)))(params)
}

class NASTIMasterHandler(simIo: SimWrapperIO, memIo: MemIO) extends NASTIModule {
  val io = new Bundle {
    val nasti = (new NASTIMasterIO).flip
    val step  = Decoupled(UInt(width=nastiXDataBits))
    val len   = Decoupled(UInt(width=nastiXDataBits))
    val lat   = Decoupled(UInt(width=nastiXDataBits))
    val fin   = Decoupled(UInt(width=nastiXDataBits)).flip
    val ins   = Vec(simIo.inMap filterNot (SimMemIO contains _._1) flatMap 
                    simIo.getIns map (_.clone))
    val outs  = Vec(simIo.outMap filterNot (SimMemIO contains _._1) flatMap 
                    simIo.getOuts map (_.clone.flip))
    val inT   = Vec(simIo.inMap  flatMap simIo.getIns  map (_.clone.flip))
    val outT  = Vec(simIo.outMap flatMap simIo.getOuts map (_.clone.flip))
    val daisy = simIo.daisy.clone.flip
    val reset_t = Bool(OUTPUT)
    val mem = new Bundle {
      val req_cmd = Vec(memIo.req_cmd.bits.clone.asOutput.flatten map {
        case (name, wire) => (s"req_cmd_${name}", wire)} flatMap simIo.genPacket)
      val req_data = Vec(memIo.req_data.bits.clone.asOutput.flatten map {
        case (name, wire) => (s"req_data_${name}", wire)} flatMap simIo.genPacket)
      val resp = Vec(memIo.resp.bits.clone.asInput.flatten map {
        case (name, wire) => (s"resp_${name}", wire)} flatMap simIo.genPacket)
    }
  }
  val channelWidth    = params(ChannelWidth)
  val addrSizeBits    = params(NASTIAddrSizeBits)
  val addrOffset      = log2Up(nastiXDataBits/8)
  val resetAddr       = (1 << addrSizeBits) - 1
  val sramRestartAddr = (1 << addrSizeBits) - 2

  require(channelWidth == nastiXDataBits, "Channel width and NASTI data width should be the same")

  /*** INPUTS ***/
  val waddr_r = RegInit(UInt(0, addrSizeBits))
  val awid_r  = RegInit(UInt(0))
  val st_wr_idle :: st_wr_write :: st_wr_ack :: Nil = Enum(UInt(), 3)
  val st_wr = RegInit(st_wr_idle)
  val do_write = st_wr === st_wr_write
  val ins = Seq(io.step) ++ io.ins ++ io.mem.req_cmd ++ io.mem.req_data ++ Seq(io.len, io.lat)
  ins.zipWithIndex foreach {case (in, i) =>
    val off = UInt(i)
    in.bits  := io.nasti.w.bits.data
    in.valid := waddr_r === off && do_write
  }
  io.reset_t            := do_write && (waddr_r === UInt(resetAddr))
  io.daisy.sram.restart := do_write && (waddr_r === UInt(sramRestartAddr))

  // Write FSM
  switch(st_wr) {
    is(st_wr_idle) {
      when(io.nasti.aw.valid && io.nasti.w.valid) {
        st_wr   := st_wr_write
        waddr_r := io.nasti.aw.bits.addr >> UInt(addrOffset)
        awid_r  := io.nasti.aw.bits.id
      }
    }
    is(st_wr_write) {
      when(Vec(ins map (_.ready))(waddr_r) || 
           waddr_r === UInt(resetAddr) || waddr_r === UInt(sramRestartAddr)) {
        st_wr := st_wr_ack
      } 
    }
    is(st_wr_ack) {
      when(io.nasti.b.ready) {
        st_wr := st_wr_idle
      }
    }
  }
  io.nasti.aw.ready    := do_write
  io.nasti.w.ready     := do_write
  io.nasti.b.valid     := st_wr === st_wr_ack
  io.nasti.b.bits.id   := awid_r
  io.nasti.b.bits.resp := Bits(0)

  /*** OUTPUTS ***/
  val raddr_r = RegInit(UInt(0, addrSizeBits))
  val arid_r  = RegInit(UInt(0))
  val st_rd_idle :: st_rd_read :: Nil = Enum(UInt(), 2)
  val st_rd = RegInit(st_rd_idle)
  val do_read = st_rd === st_rd_read

  val snapOuts = ChainType.values.toList map (t => io.daisy(t).out)
  val outs = Seq(io.fin) ++ io.outs ++ io.inT ++ io.outT ++ io.mem.resp ++ snapOuts

  outs.zipWithIndex foreach {case (out, i) =>
    val off = UInt(i)
    out.ready := raddr_r === off && do_read
  }

  // Read FSM
  switch(st_rd) {
    is(st_rd_idle) {
      when(io.nasti.ar.valid) {
        st_rd   := st_rd_read
        raddr_r := io.nasti.ar.bits.addr >> UInt(addrOffset)
        arid_r  := io.nasti.ar.bits.id
      }
    }
    is(st_rd_read) {
      when(io.nasti.r.ready) {
        st_rd   := st_rd_idle
      }
    }
  }
  io.nasti.ar.ready    := st_rd === st_rd_idle
  io.nasti.r.valid     := Vec(outs map (_.valid))(raddr_r) && do_read
  io.nasti.r.bits.data := Vec(outs map (_.bits))(raddr_r)
  io.nasti.r.bits.last := io.nasti.r.valid
  io.nasti.r.bits.id   := arid_r
  
  // TODO:
  io.daisy.regs.in.bits := UInt(0)
  io.daisy.regs.in.valid := Bool(false)
  io.daisy.trace.in.bits := UInt(0)
  io.daisy.trace.in.valid := Bool(false)
  io.daisy.sram.in.bits := UInt(0)
  io.daisy.sram.in.valid := Bool(false)

  // address assignmnet
  // 0 : step
  val inMap = simIo.genIoMap(simIo.t_ins filterNot (SimMemIO contains _._2)) map {
    case (wire, id) => wire -> (1 + id) }
  val reqMap = simIo.genIoMap(memIo.req_cmd.bits.flatten ++ memIo.req_data.bits.flatten) map {
    case (wire, id) => wire -> (1 + io.ins.size + id) }
  val traceLenAddr = 1 + io.ins.size + io.mem.req_cmd.size + io.mem.req_data.size
  val memCycleAddr = traceLenAddr + 1

  // 0 : fin
  val outMap = simIo.genIoMap(simIo.t_outs filterNot (SimMemIO contains _._2)) map {
    case (wire, id) => wire -> (1 + id) }
  val inTrMap  = simIo.inMap  map {case (wire, id) => wire -> (1 + io.outs.size + id)}
  val outTrMap = simIo.outMap map {case (wire, id) => wire -> (1 + io.outs.size + io.inT.size + id)}
  val respMap  = simIo.genIoMap(memIo.resp.bits.flatten) map {
    case (wire, id) => wire -> (1 + io.outs.size + io.inT.size + io.outT.size + id) }
  val snapOutMap = (ChainType.values.toList map (t => t -> (1 + t.id +
    io.outs.size + io.inT.size + io.outT.size + io.mem.resp.size))).toMap
}

class NASTISlaveHandler extends MIFModule with NASTIParameters {
  val io = new Bundle {
    val mem   = (new MemIO).flip
    val nasti = (new NASTISlaveIO).flip
  }
  val memBlockBytes  = params(MemBlockBytes)
  val addrSizeBits   = params(MemAddrSizeBits)
  val addrOffsetBits = log2Up(scala.math.max(memBlockBytes, nastiXDataBits/8))
  val nastiDataBeats = scala.math.max(1, mifDataBits / nastiXDataBits)

  val st_idle :: st_read :: st_start_write :: st_write :: Nil = Enum(UInt(), 4)
  val state_r = RegInit(st_idle)
  val resp_data_buf = Vec.fill(nastiDataBeats-1){Reg(UInt(width=nastiXDataBits))}
  val (read_count,  read_wrap_out)  = Counter(io.nasti.r.fire(), nastiDataBeats)
  val (write_count, write_wrap_out) = Counter(io.nasti.w.fire(), nastiDataBeats)
  val addr = Wire(UInt(width=addrSizeBits-addrOffsetBits))
  addr := io.mem.req_cmd.bits.addr
  io.mem.req_cmd.ready := 
    (state_r === st_start_write && io.nasti.aw.ready) || (state_r === st_read && io.nasti.ar.ready)

  /* Read Signals */
  // Read Address
  io.nasti.ar.bits.addr := Cat(UInt(1, 4), addr, UInt(0, addrOffsetBits))
  io.nasti.ar.bits.id   := io.mem.req_cmd.bits.tag
  io.nasti.ar.bits.len  := UInt(nastiDataBeats-1) // burst length (transfers)
  io.nasti.ar.bits.size := UInt(log2Up(scala.math.min(mifDataBits/8, nastiXDataBits/8))) // burst size (bits/beat)
  io.nasti.ar.valid     := state_r === st_read 
  // Read Data
  io.nasti.r.ready      := io.mem.resp.ready
  io.mem.resp.bits.tag  := io.nasti.r.bits.id
  io.mem.resp.valid     := read_wrap_out 
  io.mem.resp.bits.data := 
    (if (nastiDataBeats > 1) Cat(io.nasti.r.bits.data, resp_data_buf.toBits) else io.nasti.r.bits.data)

  /* Write Signals */
  // Write Address
  io.nasti.aw.bits.addr := io.nasti.ar.bits.addr
  io.nasti.aw.bits.id   := UInt(0)
  io.nasti.aw.bits.len  := io.nasti.ar.bits.len  // burst length (transfers)
  io.nasti.aw.bits.size := io.nasti.ar.bits.size // burst size (bits/beat)
  io.nasti.aw.valid     := state_r === st_start_write
  // Write Data
  io.mem.req_data.ready := io.nasti.w.bits.last
  io.nasti.w.valid      := io.mem.req_data.valid && state_r === st_write
  io.nasti.w.bits.last  := write_wrap_out
  io.nasti.w.bits.data  := Vec((0 until nastiDataBeats) map (i =>
    io.mem.req_data.bits.data((i+1)*nastiXDataBits-1, i*nastiXDataBits)))(write_count)
  // Write Response
  io.nasti.b.ready      := Bool(true)

  if (nastiDataBeats > 1) {
    when(io.nasti.r.valid && !read_wrap_out) {
      resp_data_buf(read_count) := io.nasti.r.bits.data
    }
  }

  /* FSM for memory requests */
  switch(state_r) {
    is(st_idle) {
      state_r := 
        Mux(io.mem.req_cmd.valid && !io.mem.req_cmd.bits.rw, st_read,
        Mux(io.mem.req_cmd.valid && io.mem.req_cmd.bits.rw && io.mem.req_data.valid, 
            st_start_write, st_idle))
    }
    is(st_read) {
      state_r := Mux(io.nasti.ar.ready, st_idle, st_read)
    }
    is(st_start_write) {
      state_r := Mux(io.nasti.aw.ready, st_write, st_start_write)
    }
    is(st_write) {
      state_r := Mux(io.nasti.w.fire() && write_wrap_out, st_idle, st_write)
    }
  }
}

class NASTIShimIO extends Bundle {
  val mnasti = Bundle((new NASTIMasterIO).flip, {case NASTIName => "Master"})
  val snasti = Bundle((new NASTISlaveIO).flip,  {case NASTIName => "Slave"})
  val mAddrBits = mnasti.ar.bits.nastiXAddrBits
  val mDataBits = mnasti.r.bits.nastiXDataBits
}

class NASTIShim[+T <: SimNetwork](c: =>T) extends MIFModule {
  val io = new NASTIShimIO
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

  val addrSizeBits   = params(NASTIAddrSizeBits)
  val addrOffset     = log2Up(io.mAddrBits/8)
  val memBlockBytes  = params(MemBlockBytes)
  val memBlockOffset = log2Up(memBlockBytes)

  val arb    = Module(new MemArbiter(SimMemIO.size+1))
  val mem    = arb.io.ins(SimMemIO.size)
  val master = Module(new NASTIMasterHandler(sim.io, mem), {case NASTIName => "Master"})
  val slave  = Module(new NASTISlaveHandler,               {case NASTIName => "Slave"})
  val reqCmdChannels = mem.req_cmd.bits.flatten map {case (name, wire) => 
    s"req_cmd_${name}" -> wire} flatMap (sim.genChannels(_)(this, false))
  val reqDataChannels = mem.req_data.bits.flatten map {case (name, wire) =>
    s"req_data_${name}" -> wire} flatMap (sim.genChannels(_)(this, false))
  val respChannels = mem.resp.bits.flatten map {case (name, wire) =>
    s"resp_${name}" -> wire} flatMap (sim.genChannels(_)(this, false))

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
  master.io.nasti <> io.mnasti
  master.io.step.ready := !tickCounter.orR && !tockCounter.orR
  when(master.io.step.fire()) { 
    tickCounter := master.io.step.bits 
    tockCounter := master.io.step.bits 
  }.elsewhen(master.io.reset_t) {
    tockCounter := UInt(1)
  }
  master.io.fin.valid := Bool(true)
  master.io.fin.bits  := !tockCounter.orR
  master.io.len  <> sim.io.traceLen
  master.io.ins  <> Vec(in_bufs  map (_.io.enq))
  master.io.outs <> Vec(out_bufs map (_.io.deq)) 
  master.io.inT  <> sim.io.inT
  master.io.outT <> sim.io.outT
  master.io.mem.req_cmd  <> Vec(reqCmdChannels  map (_.io.in))
  master.io.mem.req_data <> Vec(reqDataChannels map (_.io.in))
  master.io.mem.resp     <> Vec(respChannels    map (_.io.out))
  sim.reset := master.io.reset_t
  master.io.daisy.regs.in  <> sim.io.daisy.regs.in
  master.io.daisy.trace.in <> sim.io.daisy.trace.in
  master.io.daisy.sram.in  <> sim.io.daisy.sram.in
  // bulk connection not working due to empty outputs for now
  master.io.daisy.regs.out.bits  := sim.io.daisy.regs.out.bits
  master.io.daisy.regs.out.valid := sim.io.daisy.regs.out.valid
  sim.io.daisy.regs.out.ready := master.io.daisy.regs.out.ready
  master.io.daisy.trace.out.bits  := sim.io.daisy.trace.out.bits
  master.io.daisy.trace.out.valid := sim.io.daisy.trace.out.valid
  sim.io.daisy.trace.out.ready := master.io.daisy.trace.out.ready
  master.io.daisy.sram.out.bits  := sim.io.daisy.sram.out.bits
  master.io.daisy.sram.out.valid := sim.io.daisy.sram.out.valid
  sim.io.daisy.sram.out.ready := master.io.daisy.sram.out.ready
  sim.io.daisy.sram.restart := master.io.daisy.sram.restart

  // Slave Connection
  slave.io.nasti <> io.snasti
  slave.io.mem <> arb.io.out

  // Memory Connection
  for (i <-0 until SimMemIO.size) {
    val conv = Module(new ChannelMemIOConverter)
    conv setName s"mem_conv_${i}"
    conv.reset := master.io.reset_t
    conv.io.latency  <> master.io.lat
    conv.io.sim_mem  <> (SimMemIO(i), sim.io)
    conv.io.host_mem <> arb.io.ins(i)
  }

  (mem.req_cmd.bits.flatten foldLeft 0)(sim.connectInput(_, _, reqCmdChannels))
  mem.req_cmd.valid := (reqCmdChannels foldLeft Bool(true))(_ && _.io.out.valid)
  reqCmdChannels foreach (_.io.out.ready := mem.req_cmd.ready)

  (mem.req_data.bits.flatten foldLeft 0)(sim.connectInput(_, _, reqDataChannels))
  mem.req_data.valid := (reqDataChannels foldLeft Bool(true))(_ && _.io.out.valid)
  reqDataChannels foreach (_.io.out.ready := mem.req_data.ready)

  (mem.resp.bits.flatten foldLeft 0)(sim.connectOutput(_, _, respChannels))
  mem.resp.ready := (reqDataChannels foldLeft Bool(true))(_ && _.io.in.ready)
  respChannels foreach (_.io.in.valid := mem.resp.valid)

  transforms.init(this)
}
