package midas.models.sram

import chisel3._
import chisel3.util.{Mux1H, Decoupled, RegEnable, log2Ceil}

class RegfileModelGen(val depth: Int, val dataWidth: Int, val nReads: Int, val nWrites: Int) extends ModelGenerator {
  assert(depth > 0)
  assert(dataWidth > 0)
  assert(nReads > 0)
  assert(nWrites > 0)
  val emitModel = () => new RegfileChiselModel(depth, dataWidth, nReads, nWrites)
  val emitRTLImpl = () => new RegfileChiselRTL(depth, dataWidth, nReads, nWrites)
}

class ReadCmd(val depth: Int, val dataWidth: Int) extends Bundle {
  val en = Bool()
  val addr = UInt(log2Ceil(depth).W)
}

class WriteCmd(val depth: Int, val dataWidth: Int) extends Bundle {
  val en = Bool()
  val mask = Bool()
  val addr = UInt(log2Ceil(depth).W)
  val data = UInt(dataWidth.W)
  def active = en && mask
}

class RegfileRTLIO(val depth: Int, val dataWidth: Int, val nReads: Int, val nWrites: Int) extends Bundle {
  val read_cmds = Input(Vec(nReads, new ReadCmd(depth, dataWidth)))
  val read_resps = Output(Vec(nReads, UInt(dataWidth.W)))
  val write_cmds = Input(Vec(nWrites, new WriteCmd(depth, dataWidth)))
}

class RegfileChiselRTL(val depth: Int, val dataWidth: Int, val nReads: Int, val nWrites: Int) extends Module {
  val channels = IO(new RegfileRTLIO(depth, dataWidth, nReads, nWrites))
  val data = Reg(Vec(depth, UInt(dataWidth.W)))

  data.foreach({
    d => when (reset.asBool) { d := 0.U }
  })

  (channels.read_cmds zip channels.read_resps).foreach({
    case (c, r) => r := data(c.addr)
  })

  channels.write_cmds.foreach({
    c => when (c.active && !reset.asBool) { data(c.addr) := c.data }
  })
}

class RegfileModelIO(val depth: Int, val dataWidth: Int, val nReads: Int, val nWrites: Int) extends Bundle {
  val reset = Flipped(Decoupled(Bool()))
  val read_cmds = Vec(nReads, Flipped(Decoupled(new ReadCmd(depth, dataWidth))))
  val read_resps = Vec(nReads, Decoupled(UInt(dataWidth.W)))
  val write_cmds = Vec(nWrites, Flipped(Decoupled(new WriteCmd(depth, dataWidth))))
}

class RegfileChiselModel(val depth: Int, val dataWidth: Int, val nReads: Int, val nWrites: Int) extends Module {
  val channels = IO(new RegfileModelIO(depth, dataWidth, nReads, nWrites))

  val data = Reg(Vec(depth, UInt(dataWidth.W)))

  def bitwise(a: Seq[Bool], b: Seq[Bool], f: (Bool, Bool) => Bool): Seq[Bool] = {
    (a zip b).map{ case (x, y) => f(x, y) }
  }

  def prioritize(reqs: Seq[Bool]): Seq[Bool] = {
    bitwise((reqs.scanLeft(true.B) { case (open, claim) => open && !claim }).init, reqs, (_ && _))
  }

  // Registers to track target reset -> gates clocks and resets registers
  val reset_token = Reg(Bool())
  val has_reset_token = RegInit(false.B)

  // Unpacking inputs
  val reads_cmd_valid = (0 until nReads).map(i => channels.read_cmds(i).valid)
  val reads_cmd_addr = (0 until nReads).map(i => channels.read_cmds(i).bits.addr)
  val reads_resp_ready = (0 until nReads).map(i => channels.read_resps(i).ready)
  val writes_cmd_valid = (0 until nWrites).map(i => channels.write_cmds(i).valid)
  val writes_cmd_en = (0 until nWrites).map(i => channels.write_cmds(i).bits.active)
  val writes_cmd_addr = (0 until nWrites).map(i => channels.write_cmds(i).bits.addr)
  val writes_cmd_data = (0 until nWrites).map(i => channels.write_cmds(i).bits.data)

  // State tracking registers
  val reads_cmd_fired = Seq.fill(nReads){ Reg(Bool()) }
  val reads_resp_fired = Seq.fill(nReads){ Reg(Bool()) }
  val writes_cmd_fired = Seq.fill(nWrites){ Reg(Bool()) }

  // Can the input channels fire this cycle if they have priority?
  val reads_cmd_can_fire = bitwise(reads_cmd_valid, reads_cmd_fired, (_ && !_))
  // Resolve the priority without making a given channel's ready depend on its valid
  val reads_cmd_ready = prioritize(reads_cmd_can_fire)
  reads_cmd_ready.zipWithIndex foreach { case (rdy, idx) => channels.read_cmds(idx).ready := rdy }
  // Input fire signals
  val reads_cmd_fire = bitwise(reads_cmd_ready, reads_cmd_valid, (_ && _))

  // All read commands finished or finishing this cycle? Divides read and write phases.
  val read_cmds_done_next_cycle = bitwise(reads_cmd_fired, reads_cmd_fire, (_ || _)).reduce(_ && _)

  // Do the read
  val raddr = Mux1H(reads_cmd_fire, reads_cmd_addr)
  val rdata = data(raddr)

  // Registers to hold read data while outputs block
  val reads_resp_data = reads_cmd_fire.map(RegEnable(rdata, _))
  reads_resp_data.zipWithIndex foreach { case (data, idx) => channels.read_resps(idx).bits := data }
  // Valid signals for responses
  val reads_resp_valid = bitwise(reads_cmd_fired, reads_resp_fired, (_ && !_))
  reads_resp_valid.zipWithIndex foreach { case (valid, idx) => channels.read_resps(idx).valid := valid }
  // Output fire signals
  val reads_resp_fire = bitwise(reads_resp_ready, reads_resp_valid, (_ && _))

  // All read responses done this cycle?
  val read_resps_done_next_cycle = bitwise(reads_resp_fired, reads_resp_fire, (_ || _)).reduce(_ && _)

  // Can the write commands fire this cycle if they have priority?
  val writes_cmd_can_fire = bitwise(writes_cmd_valid, writes_cmd_fired, (_ && !_ && read_cmds_done_next_cycle && has_reset_token))
  // Resolve the priority
  val writes_cmd_ready = prioritize(writes_cmd_can_fire)
  writes_cmd_ready.zipWithIndex foreach { case (rdy, idx) => channels.write_cmds(idx).ready := rdy }
  // Input fire signals
  val writes_cmd_fire = bitwise(writes_cmd_ready, writes_cmd_valid, (_ && _))

  // Do the write
  val wen = Mux1H(writes_cmd_fire, bitwise(writes_cmd_en, writes_cmd_fire, (_ && _)))
  val waddr = Mux1H(writes_cmd_fire, writes_cmd_addr)
  val wdata = Mux1H(writes_cmd_fire, writes_cmd_data)
  when (wen && !reset_token) { // gate write on target reset
    data(waddr) := wdata
  }
  for (d <- data) {
    // Reset under host reset, since the RTL impl. gets an 'early'
    // reset, as it is next-ed during host reset. Regardless, early
    // reset is necessary to guard the first read.
    // Also, only reset after reads are done
    when (reset.asBool || (has_reset_token && read_resps_done_next_cycle && reset_token)) {
      d := 0.U
    }
  }

  // All write commands finished or finishing this cycle?
  val writes_done_next_cycle = bitwise(writes_cmd_fired, writes_cmd_fire, (_ || _)).reduce(_ && _)

  // State updates
  val done = read_resps_done_next_cycle && writes_done_next_cycle && has_reset_token
  def updateState(fired: Bool, fire: Bool): Unit = {
    fired := Mux(reset.asBool || done, false.B, fired || fire)
  }

  (reads_cmd_fired zip reads_cmd_fire).foreach { case (fired, fire) => updateState(fired, fire) }
  (reads_resp_fired zip reads_resp_fire).foreach { case (fired, fire) => updateState(fired, fire) }
  (writes_cmd_fired zip writes_cmd_fire).foreach { case (fired, fire) => updateState(fired, fire) }

  // Track target reset tokens
  channels.reset.ready := !has_reset_token || done
  when (channels.reset.fire) {
    reset_token := channels.reset.bits
    has_reset_token := true.B
  } .elsewhen (done) {
    has_reset_token := false.B
  }


}

