package midas
package models

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.util.ParameterizedBundle
import junctions._
import midas.core._
import midas.passes.Fame1Annotator
import midas.widgets._

class MMRegIO(val cfg: BaseConfig) extends Bundle {
  val readMaxReqs = Input(UInt(32.W))
  val writeMaxReqs = Input(UInt(32.W))
//  val totalReads = Output(UInt(32.W))
//  val totalWrites = Output(UInt(32.W))
//  val readOutstandingHistogram = Output(Vec(cfg.maxReads, UInt(32.W)))
//  val writeOutstandingHistogram = Output(Vec(cfg.maxWrites, UInt(32.W)))
}

abstract class TimingModelIO(cfg: BaseConfig)(implicit val p: Parameters)
    extends ParameterizedBundle()(p) {
  val tNasti = Flipped(new NastiIO)
  val egressReq = new EgressReq
  val egressResp = Flipped(new EgressResp)
  // This sub-bundle contains all the programmable fields of the model
  val mmReg: MMRegIO
}

abstract class TimingModel(val cfg: BaseConfig)(implicit val p: Parameters) extends Module
  with IngressModuleParameters with EgressUnitParameters with HasNastiParameters{
  val io: TimingModelIO

  // Regulates the return of beats to the target memory system
  val backend: AXI4Backend

  val tNasti = io.tNasti

  val tCycle = RegInit(UInt(0, width = 64))
  tCycle := tCycle + 1.U

  val writeMaxReqs = io.mmReg.writeMaxReqs
  val readMaxReqs = io.mmReg.readMaxReqs

  val pendingReads = Module(new SatUpDownCounter(cfg.maxReads))
  pendingReads.io.inc := tNasti.ar.fire()
  pendingReads.io.dec := tNasti.r.fire() && tNasti.r.bits.last
  tNasti.ar.ready := ~pendingReads.io.full

  val pendingAWReq = Module(new SatUpDownCounter(cfg.maxWrites))
  pendingAWReq.io.inc := tNasti.aw.fire()
  pendingAWReq.io.dec := tNasti.b.fire()
  tNasti.aw.ready := ~pendingAWReq.io.full

  val pendingWReq = Module(new SatUpDownCounter(cfg.maxWrites))
  pendingWReq.io.inc := tNasti.w.fire() && tNasti.w.bits.last
  pendingWReq.io.dec := tNasti.b.fire()
  tNasti.w.ready := ~pendingWReq.io.full

  pendingReads.io.max := readMaxReqs
  pendingAWReq.io.max := writeMaxReqs
  pendingWReq.io.max := writeMaxReqs

  // Track acked AW and W requests; newWReq = high when a (AW, W) pair is complete
  val newWReq = ((pendingWReq.io.value > pendingAWReq.io.value) && pendingAWReq.io.inc) ||
                     ((pendingWReq.io.value < pendingAWReq.io.value) && pendingWReq.io.inc) ||
                     (pendingWReq.io.inc && pendingWReq.io.inc)

  val awQueue = Module(new Queue(new NastiWriteAddressChannel, cfg.maxWrites, flow = true))
  awQueue.io.enq.bits := tNasti.aw.bits
  awQueue.io.enq.valid := tNasti.aw.fire()
  awQueue.io.deq.ready := newWReq


  // Backend: Handle the release of beats back to the target
  tNasti.b <> backend.io.b
  tNasti.r <> backend.io.r
  io.egressReq <> backend.io.egressReq
  backend.io.egressResp <> io.egressResp

  /*
  val totalReads = RegInit(0.U(32.W))
  val totalWrites = RegInit(0.U(32.W))
  when(pendingReads.io.inc){ totalReads := totalReads + 1.U }
  when(newWReq){ totalWrites := totalWrites + 1.U}
  io.mmReg.totalReads := totalReads
  io.mmReg.totalWrites := totalWrites

  val readOutstandingHistogram = Seq.fill(cfg.maxReads)(RegInit(0.U(32.W)))
  val writeOutstandingHistogram = Seq.fill(cfg.maxWrites)(RegInit(0.U(32.W)))

  (readOutstandingHistogram.zipWithIndex).foreach { case (count, idx) =>
    count := Mux(pendingReads.io.value === idx.U, count + 1.U, count)
  }
  (writeOutstandingHistogram.zipWithIndex).foreach { case (count, idx) =>
    count := Mux(pendingAWReq.io.value === idx.U, count + 1.U, count)
  }

  io.mmReg.readOutstandingHistogram := readOutstandingHistogram
  io.mmReg.writeOutstandingHistogram := writeOutstandingHistogram
  */
  // all of the relevant state
}


