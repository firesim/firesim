package midas.models.sram

import chisel3._
import chisel3.util.{Mux1H, Decoupled, RegEnable, log2Ceil, Enum}
import chisel3.experimental.{MultiIOModule, dontTouch}
//import chisel3.experimental.ChiselEnum
import chisel3.experimental.{DataMirror, requireIsChiselType}
import collection.immutable.ListMap

class AsyncMemModelGen(val depth: Int, val dataWidth: Int) extends ModelGenerator {
  assert(depth > 0)
  assert(dataWidth > 0)
  val emitModel = () => new AsyncMemChiselModel(depth, dataWidth)
  val emitRTLImpl = () => new AsyncMemChiselRTL(depth, dataWidth)
}

class AsyncMemChiselRTL(val depth: Int, val dataWidth: Int) extends MultiIOModule {
  val channels = IO(new RegfileRTLIO(depth, dataWidth, 1, 1))
  val data = Mem(depth, UInt(dataWidth.W))

  channels.read_resps(0) := data.read(channels.read_cmds(0).addr)
  when (channels.write_cmds(0).active && !reset.toBool()) {
    data.write(channels.write_cmds(0).addr, channels.write_cmds(0).data)
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

class AsyncMemChiselModel(val depth: Int, val dataWidth: Int) extends MultiIOModule {
  import AsyncMemChiselModel.ReadState._

  val channels = IO(new RegfileModelIO(depth, dataWidth, 1, 1))
  dontTouch(channels)
  val data = SyncReadMem(depth, UInt(dataWidth.W))
  val read_data = data.read(channels.read_cmds(0).bits.addr)

  val read_state = RegInit(start)
  val read_resp_data = Reg(UInt(dataWidth.W))
  val read_access_granted = read_state === start && channels.read_cmds(0).valid
  val outputs_responded_or_firing = read_state === responded || channels.read_resps(0).fire
  channels.read_resps(0).bits := Mux(read_state === active, read_data, read_resp_data)
  channels.read_resps(0).valid := read_state === active || read_state === generated

  val write_complete = RegInit(false.B)
  val write_access_granted = outputs_responded_or_firing && !write_complete && channels.write_cmds(0).valid && channels.reset.valid
  val advance_cycle = write_complete || write_access_granted

  channels.reset.ready := write_access_granted
  channels.read_cmds(0).ready := read_access_granted
  channels.write_cmds(0).ready := write_access_granted

  when (advance_cycle) {
    read_state := start
  } .elsewhen (read_state === start && read_access_granted) {
    read_state := active
  } .elsewhen (read_state === active) {
    read_state := Mux(channels.read_resps(0).fire, responded, generated)
    read_resp_data := read_data
  } .elsewhen (read_state === generated && channels.read_resps(0).fire) {
    read_state := responded
  }

  when (advance_cycle) {
    write_complete := false.B
  } .elsewhen (write_access_granted) {
    write_complete := true.B
  }

  when (write_access_granted && channels.write_cmds(0).bits.active && !channels.reset.bits && !reset.toBool()) {
    data.write(channels.write_cmds(0).bits.addr, channels.write_cmds(0).bits.data)
  }

}
