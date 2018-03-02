package midas
package models

import freechips.rocketchip.config.Parameters
import freechips.rocketchip.util.ParameterizedBundle
import junctions._

import chisel3._
import chisel3.util._

import midas.core._
import midas.passes.Fame1Annotator
import midas.widgets._

import Console.{UNDERLINED, RESET}

// Automatically bound to simulation-memory-mapped. registers Extends this
// bundle to add additional programmable values and instrumentation
abstract class MMRegIO(cfg: BaseConfig) extends Bundle with HasProgrammableRegisters {
  val (totalReads, totalWrites) = if (cfg.params.xactionCounters) {
    (Some(Output(UInt(32.W))),  Some(Output(UInt(32.W))))
  } else {
    (None, None)
  }

  val llc = if (cfg.useLLCModel) Some(new LLCProgrammableSettings(cfg.params.llcKey.get)) else None

  // Instrumentation Registers
  val bins = cfg.params.occupancyHistograms.getOrElse(0)
  val readOutstandingHistogram = Output(Vec(bins, UInt(32.W)))
  val writeOutstandingHistogram = Output(Vec(bins, UInt(32.W)))

  // Implemented by each timing model to query runtime values for its
  // programmable settings
  def requestSettings(): Unit

  // Called by MidasMemModel to fetch all programmable settings for the timing
  // model. These are concatenated with functional model settings
  def getTimingModelSettings(): Seq[(String, String)] = {
    // First invoke the timing model specific method
    requestSettings()
    // Finally set everything that hasn't already been set
    llc.foreach({ _.setLLCSettings() })
    Console.println(s"\n${UNDERLINED}Remaining Free Parameters${RESET}")
    setUnboundSettings()
    getSettings()
  }
}

abstract class TimingModelIO(cfg: BaseConfig)(implicit val p: Parameters) extends Bundle {
  val tNasti = Flipped(new NastiIO)
  val egressReq = new EgressReq
  val egressResp = Flipped(new EgressResp)
  // This sub-bundle contains all the programmable fields of the model
  val mmReg: MMRegIO
}

abstract class TimingModel(val cfg: BaseConfig)(implicit val p: Parameters) extends Module
    with IngressModuleParameters with EgressUnitParameters with HasNastiParameters {
  val io: TimingModelIO

  // Regulates the return of beats to the target memory system
  val tNasti = io.tNasti
  // Request channels presented to DRAM models
  val nastiReqIden = Module(new IdentityModule(new NastiReqChannels))
  val nastiReq = nastiReqIden.io.out
  val wResp = Wire(Decoupled(new WriteResponseMetaData))
  val rResp = Wire(Decoupled(new ReadResponseMetaData))


  val tCycle = RegInit(0.U(64.W))
  tCycle := tCycle + 1.U

  val pendingReads = SatUpDownCounter(cfg.maxReads)
  pendingReads.inc := tNasti.ar.fire()
  pendingReads.dec := tNasti.r.fire() && tNasti.r.bits.last

  val pendingAWReq = SatUpDownCounter(cfg.maxWrites)
  pendingAWReq.inc := tNasti.aw.fire()
  pendingAWReq.dec := tNasti.b.fire()

  val pendingWReq = SatUpDownCounter(cfg.maxWrites)
  pendingWReq.inc := tNasti.w.fire() && tNasti.w.bits.last
  pendingWReq.dec := tNasti.b.fire()

  assert(!tNasti.ar.valid ||
    (tNasti.ar.bits.len === 0.U || tNasti.ar.bits.size === log2Ceil(nastiXDataBits/8).U),
    "Illegal ar request: memory model only supports full-width bursts")
  assert(!tNasti.ar.valid || (tNasti.ar.bits.burst === NastiConstants.BURST_INCR),
    "Illegal ar request: memory model only supports incrementing bursts")

  assert(!tNasti.aw.valid ||
    (tNasti.aw.bits.len === 0.U || tNasti.aw.bits.size === log2Ceil(nastiXDataBits/8).U),
    "Illegal aw request: memory model only supports full-width bursts")
  assert(!tNasti.aw.valid || (tNasti.aw.bits.burst === NastiConstants.BURST_INCR),
    "Illegal aw request: memory model only supports incrementing bursts")

  // Release; returns responses to target
  val xactionRelease = Module(new AXI4Releaser)
  tNasti.b <> xactionRelease.io.b
  tNasti.r <> xactionRelease.io.r
  io.egressReq <> xactionRelease.io.egressReq
  xactionRelease.io.egressResp <> io.egressResp

  if (cfg.useLLCModel) {
    // Drop the LLC model inline
    val llc_model = Module(new LLCModel(cfg))
    llc_model.io.settings <> io.mmReg.llc.get
    llc_model.io.memRResp <> rResp
    llc_model.io.memWResp <> wResp
    llc_model.io.req.fromNasti(io.tNasti)
    nastiReqIden.io.in <> llc_model.io.memReq
    xactionRelease.io.nextWrite <> llc_model.io.wResp
    xactionRelease.io.nextRead <> llc_model.io.rResp
  } else {
    nastiReqIden.io.in.fromNasti(io.tNasti)
    xactionRelease.io.nextWrite <> wResp
    xactionRelease.io.nextRead <> rResp
  }


  if (cfg.params.xactionCounters) {
    val totalReads = RegInit(0.U(32.W))
    val totalWrites = RegInit(0.U(32.W))
    when(pendingReads.inc){ totalReads := totalReads + 1.U }
    when(pendingAWReq.inc){ totalWrites := totalWrites + 1.U}
    io.mmReg.totalReads foreach { _ := totalReads }
    io.mmReg.totalWrites foreach { _ := totalWrites }
  }

  cfg.params.occupancyHistograms foreach { num_bins =>
    require(isPow2(num_bins))
    val readOutstandingHistogram = Seq.fill(num_bins)(RegInit(0.U(32.W)))
    val writeOutstandingHistogram = Seq.fill(num_bins)(RegInit(0.U(32.W)))

    (readOutstandingHistogram.zipWithIndex).foreach { case (count, idx) =>
      count := Mux(pendingReads.value.head(log2Ceil(num_bins)) === idx.U, count + 1.U, count)
    }
    (writeOutstandingHistogram.zipWithIndex).foreach { case (count, idx) =>
      count := Mux(pendingAWReq.value.head(log2Ceil(num_bins)) === idx.U, count + 1.U, count)
    }

    io.mmReg.readOutstandingHistogram := readOutstandingHistogram
    io.mmReg.writeOutstandingHistogram := writeOutstandingHistogram
  }
}


// A class of simple timing models that has independently programmable bounds on
// the number of reads and writes the model will accept.
//
// This is in contrast to more complex DRAM models that propogate backpressure
// from shared structures back to the AXI4 request channels.
abstract class SplitTransactionMMRegIO(cfg: BaseConfig) extends MMRegIO(cfg) {
  val readMaxReqs = Input(UInt(log2Ceil(cfg.maxReads+1).W))
  val writeMaxReqs = Input(UInt(log2Ceil(cfg.maxWrites+1).W))

  val maxReqRegisters = Seq(
    (writeMaxReqs -> RuntimeSetting(cfg.maxWrites,
                                  "Maximum number of target-writes the model will accept",
                                  max = Some(cfg.maxWrites))),
    (readMaxReqs  -> RuntimeSetting(cfg.maxReads,
                                  "Maximum number of target-reads the model will accept",
                                  max = Some(cfg.maxReads)))
  )
}

abstract class SplitTransactionModelIO(cfg: BaseConfig)(implicit p: Parameters)
    extends TimingModelIO(cfg) {
  // This sub-bundle contains all the programmable fields of the model
  val mmReg: SplitTransactionMMRegIO
}

abstract class SplitTransactionModel(cfg: BaseConfig)(implicit p: Parameters)
    extends TimingModel(cfg)(p) {
  override val io: SplitTransactionModelIO

  pendingReads.max := io.mmReg.readMaxReqs
  pendingAWReq.max := io.mmReg.writeMaxReqs
  pendingWReq.max  := io.mmReg.writeMaxReqs
  nastiReq.ar.ready := ~pendingReads.full
  nastiReq.aw.ready := ~pendingAWReq.full
  nastiReq.w.ready  := ~pendingWReq.full

  //recombines AW and W transactions before passing them onto the rest of the model
  val awQueue = Module(new Queue(new NastiWriteAddressChannel, cfg.maxWrites, flow = true))

  val newWReq = if (!cfg.useLLCModel) {
    ((pendingWReq.value > pendingAWReq.value) && pendingAWReq.inc) ||
    ((pendingWReq.value < pendingAWReq.value) && pendingWReq.inc) ||
    (pendingWReq.inc && pendingAWReq.inc)
  } else {
    val memWReqs = SatUpDownCounter(cfg.maxWrites)
    val newWReq = ((memWReqs.value > awQueue.io.count) && nastiReq.aw.fire) ||
                  ((memWReqs.value < awQueue.io.count) && memWReqs.inc) ||
                   (memWReqs.inc && nastiReq.aw.fire)

    memWReqs.inc := nastiReq.w.fire && nastiReq.w.bits.last
    memWReqs.dec := newWReq
    newWReq
  }

  awQueue.io.enq.bits := nastiReq.aw.bits
  awQueue.io.enq.valid := nastiReq.aw.fire()
  awQueue.io.deq.ready := newWReq
}
