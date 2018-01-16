package midas
package models

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.util.GenericParameterizedBundle
import junctions._
import midas.widgets._


case class LatencyPipeConfig(baseParams: BaseParams) extends BaseConfig(baseParams) {
  def elaborate()(implicit p:Parameters): LatencyPipe = Module(new LatencyPipe(this))
}

class LatencyPipeMMRegIO(cfg: BaseConfig) extends MMRegIO(cfg) {
  val readLatency = Input(UInt(32.W))
  val writeLatency = Input(UInt(32.W))
}

class LatencyPipeIO(cfg: LatencyPipeConfig)(implicit p: Parameters) extends TimingModelIO(cfg)(p) {
  val mmReg = new MMRegIO(cfg) {
    val readLatency = Input(UInt(32.W))
    val writeLatency = Input(UInt(32.W))
  }
}

class WritePipeEntry(key: MetaDataWidths) extends GenericParameterizedBundle(key) {
  val releaseCycle = UInt(width = 64)
  val xaction = new WriteMetaData(key)
}

class ReadPipeEntry(key: MetaDataWidths) extends GenericParameterizedBundle(key) {
  val releaseCycle = UInt(width = 64)
  val xaction = new ReadMetaData(key)
}

class LatencyPipe(cfg: LatencyPipeConfig)(implicit p: Parameters) extends TimingModel(cfg)(p) {
  lazy val io = IO(new LatencyPipeIO(cfg))

  // Configuration values
  val readLatency = io.mmReg.readLatency
  val writeLatency = io.mmReg.writeLatency

  lazy val backend = Module(new SplitAXI4Backend(cfg))
  // ***** Write Latency Pipe *****
  // Write delays are applied to the cycle upon which both the AW and W
  // transactions have completed. Since multiple AW packets may arrive
  // before the associated W packet, we queue them up.
  val writePipe = Module(new Queue(new WritePipeEntry(AXI4MetaDataWidths()), cfg.maxWrites, flow = true))

  writePipe.io.enq.valid := newWReq
  writePipe.io.enq.bits.xaction.id := awQueue.io.deq.bits.id
  writePipe.io.enq.bits.releaseCycle := writeLatency + tCycle - egressUnitDelay.U

  val writeDone = writePipe.io.deq.bits.releaseCycle <= tCycle
  backend.io.newWrite.valid := writePipe.io.deq.valid && writeDone
  backend.io.newWrite.bits := writePipe.io.deq.bits.xaction
  writePipe.io.deq.ready := backend.io.newWrite.ready && writeDone


  // ***** Read Latency Pipe *****
  val readPipe = Module(new Queue(new ReadPipeEntry(AXI4MetaDataWidths()), cfg.maxReads, flow = true))

  readPipe.io.enq.valid := tNasti.ar.fire
  readPipe.io.enq.bits.xaction.id := tNasti.ar.bits.id
  readPipe.io.enq.bits.xaction.len := tNasti.ar.bits.len
  readPipe.io.enq.bits.releaseCycle := readLatency + tCycle - egressUnitDelay.U
  // Release read responses on the appropriate cycle
  val readDone = readPipe.io.deq.bits.releaseCycle <= tCycle
  backend.io.newRead.valid := readPipe.io.deq.valid && readDone
  backend.io.newRead.bits := readPipe.io.deq.bits.xaction
  readPipe.io.deq.ready := backend.io.newRead.ready && readDone
}

