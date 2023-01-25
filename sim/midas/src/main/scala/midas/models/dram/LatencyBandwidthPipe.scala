package midas
package models

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters

import Console.{UNDERLINED, RESET}

case class LatencyPipeConfig(params: BaseParams) extends BaseConfig {
  def elaborate()(implicit p: Parameters): LatencyPipe = Module(new LatencyPipe(this))
}

class LatencyPipeMMRegIO(cfg: BaseConfig)(implicit p: Parameters) extends SplitTransactionMMRegIO(cfg){
  val readLatency = Input(UInt(32.W))
  val writeLatency = Input(UInt(32.W))

  val registers = maxReqRegisters ++ Seq(
    (writeLatency -> RuntimeSetting(30, "Write latency", min = 1)),
    (readLatency  -> RuntimeSetting(30,"Read latency", min = 1))
  )

  def requestSettings(): Unit = {
    Console.println(s"${UNDERLINED}Generating a runtime configuration for a latency-bandwidth pipe${RESET}")
  }
}

class LatencyPipeIO(val cfg: LatencyPipeConfig)(implicit p: Parameters) extends SplitTransactionModelIO()(p) {
  val mmReg = new LatencyPipeMMRegIO(cfg)
}

class WritePipeEntry(implicit val p: Parameters) extends Bundle {
  val releaseCycle = UInt(64.W)
  val xaction = new WriteResponseMetaData
}

class ReadPipeEntry(implicit val p: Parameters) extends Bundle {
  val releaseCycle = UInt(64.W)
  val xaction = new ReadResponseMetaData
}

class LatencyPipe(cfg: LatencyPipeConfig)(implicit p: Parameters) extends SplitTransactionModel(cfg)(p) {
  lazy val io = IO(new LatencyPipeIO(cfg))

  val longName = "Latency Bandwidth Pipe"
  def printTimingModelGenerationConfig: Unit = {}
  /**************************** CHISEL BEGINS *********************************/
  // Configuration values
  val readLatency = io.mmReg.readLatency
  val writeLatency = io.mmReg.writeLatency

  // ***** Write Latency Pipe *****
  // Write delays are applied to the cycle upon which both the AW and W
  // transactions have completed. Since multiple AW packets may arrive
  // before the associated W packet, we queue them up.
  val writePipe = Module(new Queue(new WritePipeEntry, cfg.maxWrites, flow = true))

  writePipe.io.enq.valid := newWReq
  writePipe.io.enq.bits.xaction := WriteResponseMetaData(awQueue.io.deq.bits)
  writePipe.io.enq.bits.releaseCycle := writeLatency + tCycle - egressUnitDelay.U

  val writeDone = writePipe.io.deq.bits.releaseCycle <= tCycle
  wResp.valid := writePipe.io.deq.valid && writeDone
  wResp.bits := writePipe.io.deq.bits.xaction
  writePipe.io.deq.ready := wResp.ready && writeDone

  assert(writePipe.io.enq.ready || !newWReq, "LBP write latency pipe would overflow.")

  // ***** Read Latency Pipe *****
  val readPipe = Module(new Queue(new ReadPipeEntry, cfg.maxReads, flow = true))

  readPipe.io.enq.valid := nastiReq.ar.fire
  readPipe.io.enq.bits.xaction := ReadResponseMetaData(nastiReq.ar.bits)
  readPipe.io.enq.bits.releaseCycle := readLatency + tCycle - egressUnitDelay.U
  // Release read responses on the appropriate cycle
  val readDone = readPipe.io.deq.bits.releaseCycle <= tCycle
  rResp.valid := readPipe.io.deq.valid && readDone
  rResp.bits := readPipe.io.deq.bits.xaction
  readPipe.io.deq.ready := rResp.ready && readDone

  assert(readPipe.io.enq.ready || !nastiReq.ar.fire, "LBP read latency pipe would overflow.")
}

