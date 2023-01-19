package midas.models.sram

import chisel3._
import chisel3.util.Enum
//import chisel3.experimental.ChiselEnum

class AsyncMemModelGen(val depth: Int, val dataWidth: Int) extends ModelGenerator {
  assert(depth > 0)
  assert(dataWidth > 0)
  val emitModel = () => new AsyncMemChiselModel(depth, dataWidth)
  val emitRTLImpl = () => new AsyncMemChiselRTL(depth, dataWidth)
}

class AsyncMemChiselRTL(val depth: Int, val dataWidth: Int, val nReads: Int = 2, val nWrites: Int = 2) extends Module {
  val channels = IO(new RegfileRTLIO(depth, dataWidth, nReads, nWrites))
  val data = Mem(depth, UInt(dataWidth.W))

  for (i <- 0 until nReads) {
    channels.read_resps(i) := data.read(channels.read_cmds(i).addr)
  }

  for (i <- 0 until nWrites) {
    val write_cmd = channels.write_cmds(i)
    def collides(c: WriteCmd) = c.active && write_cmd.active && (c.addr === write_cmd.addr)
    val collision_detected = channels.write_cmds.drop(i+1).foldLeft(false.B) {
      case (detected, cmd) => detected || collides(cmd)
    }

    when (write_cmd.active && !reset.asBool && !collision_detected) {
      data.write(write_cmd.addr, write_cmd.data)
    }
  }
}

object AsyncMemChiselModel {
  //object ReadState extends ChiselEnum {
  //  val start, active, generated, responded = Value
  //}
  object ReadState {
    lazy val start :: active :: generated :: responded :: Nil = Enum(4)
  }
}

class AsyncMemChiselModel(val depth: Int, val dataWidth: Int, val nReads: Int = 2, val nWrites: Int = 2) extends Module {

  // FSM states and helper functions
  //import AsyncMemChiselModel.ReadState
  import AsyncMemChiselModel.ReadState._
  val tupleAND = (vals: (Bool, Bool)) => vals._1 && vals._2
  val tupleOR = (vals: (Bool, Bool)) => vals._1 || vals._2

  // Channelized IO
  val channels = IO(new RegfileModelIO(depth, dataWidth, nReads, nWrites))

  // Target reset logic
  val target_reset_fired = Reg(Bool())
  val target_reset_available = target_reset_fired || channels.reset.valid
  val target_reset_reg = Reg(Bool())
  val target_reset_value = Mux(target_reset_fired, target_reset_reg, channels.reset.bits)

  // Host memory implementation
  val data = Mem(depth, UInt(dataWidth.W))
  val active_read_addr = Wire(UInt())
  val active_write_addr = Wire(UInt())
  val active_write_data = Wire(UInt())
  val active_write_en = Wire(Bool())
  val read_data_async = data.read(active_read_addr)
  val read_data = RegNext(read_data_async)
  when (active_write_en && !target_reset_value && !reset.asBool) {
    data.write(active_write_addr, active_write_data)
  }


  // Read request management and response data buffering
  val read_state = Reg(Vec(nReads, start.cloneType))
  val read_resp_data = Reg(Vec(nReads, UInt(dataWidth.W)))
  val read_access_req = (read_state zip channels.read_cmds) map { case (s, cmd) => s === start && cmd.valid }

  // Don't use priority encoder because bools catted to ints considered hard to QED
  val read_access_available = read_access_req.scanLeft(true.B)({ case (open, claim) => open && !claim }).init
  val read_access_granted = (read_access_req zip read_access_available) map tupleAND

  // Have all reads actually been performed?
  val reads_done = read_state.foldLeft(true.B) { case (others_done, s) => others_done && s =/= start }

  // This is used to overlap last read and first write -- depends on READ_FIRST implementation
  val reads_finishing = (read_state zip channels.read_cmds).foldLeft(true.B) {
    case (finishing, (s, cmd)) => finishing && (s =/= start || cmd.fire)
  }

  // Are all reads done or finishing this cycle?
  val outputs_responded_or_firing = (read_state zip channels.read_resps).foldLeft(true.B) {
    case (res, (s, resp)) => res && (s === responded || resp.fire)
  }

  // Write request management
  val write_complete = Reg(Vec(nWrites, Bool()))

  // Order writes for determinism
  val write_prereqs_met = (true.B +: write_complete.init) map { case p => p && reads_done && target_reset_available }

  // Are all writes done or finishing this cycle?
  val writes_done_or_finishing = (write_complete zip channels.write_cmds).foldLeft(true.B) {
    case (res, (complete, cmd)) => res && (complete || cmd.fire)
  }

  val advance_cycle = outputs_responded_or_firing && writes_done_or_finishing

  // Target reset state management
  channels.reset.ready := !target_reset_fired
  when (advance_cycle || reset.asBool) {
    target_reset_fired := false.B
  } .elsewhen (channels.reset.fire) {
    target_reset_fired := true.B
    target_reset_reg := channels.reset.bits
  }

  // Read state management
  active_read_addr := channels.read_cmds(0).bits.addr
  for (i <- 0 until nReads) {
    when (read_access_granted(i)) { active_read_addr := channels.read_cmds(i).bits.addr }

    channels.read_cmds(i).ready := read_state(i) === start && read_access_available(i)
    channels.read_resps(i).bits := Mux(read_state(i) === active, read_data, read_resp_data(i))
    channels.read_resps(i).valid := read_state(i) === active || read_state(i) === generated

    when (advance_cycle || reset.asBool) {
      read_state(i) := start
    } .elsewhen (read_state(i) === start && read_access_granted(i)) {
      read_state(i) := active
    } .elsewhen (read_state(i) === active) {
      read_state(i) := Mux(channels.read_resps(i).fire, responded, generated)
      read_resp_data(i) := read_data
    } .elsewhen (read_state(i) === generated && channels.read_resps(i).fire) {
      read_state(i) := responded
    }
  }

  // Write state management
  active_write_addr := channels.write_cmds(0).bits.addr
  active_write_data := channels.write_cmds(0).bits.data
  active_write_en := false.B
  for (i <- 0 until nWrites) {
    channels.write_cmds(i).ready := write_prereqs_met(i) && !write_complete(i)
    when (advance_cycle || reset.asBool) {
      write_complete(i) := false.B
    } .elsewhen (channels.write_cmds(i).fire) {
      write_complete(i) := true.B
    }
    when (channels.write_cmds(i).fire) {
      active_write_addr := channels.write_cmds(i).bits.addr
      active_write_data := channels.write_cmds(i).bits.data
      active_write_en := channels.write_cmds(i).bits.active
    }
  }

}
