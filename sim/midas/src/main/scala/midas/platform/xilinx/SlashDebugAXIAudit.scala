// See LICENSE for license details.

package midas.platform.xilinx

import chisel3._
import chisel3.util._
import freechips.rocketchip.amba.axi4._

/** Passive debug-bus trace. Reads never consume trace entries or touch the hub. */
class SlashDebugAXIAudit(params: AXI4BundleParameters) extends Module {
  val io             = IO(new Bundle {
    val axi        = Flipped(new AXI4Bundle(params))
    val hostAR     = Input(Bool())
    val hubAR      = Input(Bool())
    val hubAddress = Input(UInt(21.W))
    val hubR       = Input(Bool())
    val hubData    = Input(UInt(32.W))
    val hubResp    = Input(UInt(2.W))
    val hubLast    = Input(Bool())
    val axisRX     = Input(Bool())
    val axisData   = Input(UInt(32.W))
    val axisLast   = Input(Bool())
    val axisTX     = Input(Bool())
  })
  val hostReads      = RegInit(0.U(32.W))
  val hubReads       = RegInit(0.U(32.W))
  val hubResponses   = RegInit(0.U(32.W))
  val hubErrors      = RegInit(0.U(32.W))
  val lastAddress    = RegInit(0.U(21.W))
  val lastData       = RegInit(0.U(32.W))
  val lastResponse   = RegInit(0.U(3.W))
  val traceCount     = RegInit(0.U(7.W))
  val dataTrace      = Reg(Vec(64, UInt(32.W)))
  val metaTrace      = Reg(Vec(64, UInt(32.W)))
  val axisWords      = RegInit(0.U(32.W))
  val axisTraceCount = RegInit(0.U(7.W))
  val axisTrace      = Reg(Vec(64, UInt(33.W)))
  val axisSent       = RegInit(0.U(32.W))
  when(io.axisTX) { axisSent := axisSent + 1.U }
  when(io.axisRX) {
    axisWords := axisWords + 1.U
    when(axisTraceCount < 64.U) {
      axisTrace(axisTraceCount(5, 0)) := Cat(io.axisLast, io.axisData)
      axisTraceCount                  := axisTraceCount + 1.U
    }
  }
  when(io.hostAR) { hostReads := hostReads + 1.U }
  when(io.hubAR) { hubReads := hubReads + 1.U; lastAddress := io.hubAddress }
  when(io.hubR) {
    hubResponses := hubResponses + 1.U
    lastData     := io.hubData
    lastResponse := Cat(io.hubLast, io.hubResp)
    when(io.hubResp =/= 0.U) { hubErrors := hubErrors + 1.U }
    when(traceCount < 64.U) {
      dataTrace(traceCount(5, 0)) := io.hubData
      metaTrace(traceCount(5, 0)) := Cat(io.hubLast, io.hubResp, 0.U(8.W), lastAddress)
      traceCount                  := traceCount + 1.U
    }
  }

  // Independent AW/W acceptance; writes are harmless acknowledgements.
  val awPending = RegInit(false.B)
  val wPending  = RegInit(false.B)
  val writeId   = Reg(UInt(params.idBits.W))
  io.axi.aw.ready  := !awPending
  io.axi.w.ready   := !wPending
  when(io.axi.aw.fire) { awPending := true.B; writeId := io.axi.aw.bits.id }
  when(io.axi.w.fire) { wPending := true.B }
  io.axi.b.valid   := awPending && wPending
  io.axi.b.bits    := 0.U.asTypeOf(io.axi.b.bits)
  io.axi.b.bits.id := writeId
  when(io.axi.b.fire) { awPending := false.B; wPending := false.B }

  val readValid = RegInit(false.B)
  val readData   = Reg(UInt(32.W))
  val readId     = Reg(UInt(params.idBits.W))
  val offset     = io.axi.ar.bits.addr(11, 0)
  val traceIndex = (offset - 0x100.U)(8, 3)
  val traceData  = Mux(offset(2), metaTrace(traceIndex), dataTrace(traceIndex))
  val axisIndex  = (offset - 0x400.U)(8, 3)
  val axisValue  = Mux(offset(2), axisTrace(axisIndex)(32).asUInt, axisTrace(axisIndex)(31, 0))
  val registers  = MuxLookup(offset, 0.U(32.W))(
    Seq(
      0x00.U -> "h56384158".U,
      0x04.U -> 1.U,
      0x08.U -> hostReads,
      0x0c.U -> hubReads,
      0x10.U -> hubResponses,
      0x14.U -> hubErrors,
      0x18.U -> lastAddress,
      0x1c.U -> lastData,
      0x20.U -> lastResponse,
      0x24.U -> traceCount,
      0x28.U -> axisWords,
      0x2c.U -> axisTraceCount,
      0x30.U -> axisSent,
    )
  )
  io.axi.ar.ready := !readValid || io.axi.r.ready
  io.axi.r.valid     := readValid
  io.axi.r.bits      := 0.U.asTypeOf(io.axi.r.bits)
  io.axi.r.bits.data := readData
  io.axi.r.bits.id   := readId
  io.axi.r.bits.last := true.B
  when(io.axi.r.fire) { readValid := false.B }
  when(io.axi.ar.fire) {
    readValid := true.B
    readId    := io.axi.ar.bits.id
    readData  := Mux(
      offset >= 0x100.U && offset < 0x300.U,
      traceData,
      Mux(offset >= 0x400.U && offset < 0x600.U, axisValue, registers),
    )
  }
}
