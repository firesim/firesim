package midas
package models

import org.chipsalliance.cde.config.Parameters
import junctions._

import chisel3._
import chisel3.util._

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
  val (totalReadBeats, totalWriteBeats) = if (cfg.params.beatCounters) {
    (Some(Output(UInt(32.W))),  Some(Output(UInt(32.W))))
  } else {
    (None, None)
  }

  val llc = if (cfg.useLLCModel) Some(new LLCProgrammableSettings(cfg.params.llcKey.get)) else None

  // Instrumentation Registers
  val (readBins, writeBins) = cfg.params.occupancyHistograms match {
    case Nil         => (Nil, Nil)
    case binMaximums =>
      val readBins  = binMaximums.filter(_ < cfg.params.maxReads) :+ cfg.params.maxReads
      val writeBins = binMaximums.filter(_ < cfg.params.maxWrites) :+ cfg.params.maxWrites
      (readBins, writeBins)
  }

  val readOutstandingHistogram  = Output(Vec(readBins.size, UInt(32.W)))
  val writeOutstandingHistogram = Output(Vec(writeBins.size, UInt(32.W)))

  val targetCycle = if (cfg.params.targetCycleCounter) Some(Output(UInt(32.W))) else None

  // Implemented by each timing model to query runtime values for its
  // programmable settings
  def requestSettings(): Unit

  // Called by MidasMemModel to fetch all programmable settings for the timing
  // model. These are concatenated with functional model settings
  def getTimingModelSettings(): Seq[(String, BigInt)] = {
    // First invoke the timing model specific method
    requestSettings()
    // Finally set everything that hasn't already been set
    llc.foreach({ _.setLLCSettings() })
    Console.println(s"\n${UNDERLINED}Remaining Free Parameters${RESET}")
    setUnboundSettings()
    getSettings()
  }
}

abstract class TimingModelIO(implicit val p: Parameters) extends Bundle {
  val tNasti = Flipped(new NastiIO)
  val egressReq = new EgressReq
  val egressResp = Flipped(new EgressResp)
  // This sub-bundle contains all the programmable fields of the model
  val mmReg: MMRegIO
}

abstract class TimingModel(val cfg: BaseConfig)(implicit val p: Parameters) extends Module
    with IngressModuleParameters with EgressUnitParameters with HasNastiParameters {

  // Concrete timing models must implement io with the MMRegIO sub-bundle
  // containing all of the requisite runtime-settings and instrumentation brought
  // out as inputs and outputs respectively. See MMRegIO above.
  val io: TimingModelIO
  val longName: String
  // Implemented by concrete timing models to describe their configuration during
  // chisel elaboration
  protected def printTimingModelGenerationConfig: Unit

  def printGenerationConfig: Unit = {
    println("  Timing Model Class: " + longName)
    printTimingModelGenerationConfig
  }

  /**************************** CHISEL BEGINS *********************************/
  // Regulates the return of beats to the target memory system
  val tNasti = io.tNasti
  // Request channels presented to DRAM models
  val nastiReqIden = Module(new IdentityModule(new NastiReqChannels))
  val nastiReq = nastiReqIden.io.out
  val wResp = Wire(Decoupled(new WriteResponseMetaData))
  val rResp = Wire(Decoupled(new ReadResponseMetaData))

  val monitor = Module(new MemoryModelMonitor(cfg))
  monitor.axi4 := io.tNasti

  val tCycle = RegInit(0.U(64.W))
  tCycle := tCycle + 1.U
  io.mmReg.targetCycle.foreach({ _ := tCycle })


  val pendingReads = SatUpDownCounter(cfg.maxReads)
  pendingReads.inc := tNasti.ar.fire
  pendingReads.dec := tNasti.r.fire && tNasti.r.bits.last

  val pendingAWReq = SatUpDownCounter(cfg.maxWrites)
  pendingAWReq.inc := tNasti.aw.fire
  pendingAWReq.dec := tNasti.b.fire

  val pendingWReq = SatUpDownCounter(cfg.maxWrites)
  pendingWReq.inc := tNasti.w.fire && tNasti.w.bits.last
  pendingWReq.dec := tNasti.b.fire

  assert(!tNasti.ar.valid || (tNasti.ar.bits.burst === NastiConstants.BURST_INCR)
    || ((tNasti.ar.bits.burst === NastiConstants.BURST_FIXED) && tNasti.ar.bits.len === 0.U),
    "Illegal ar request: memory model only supports incrementing or fixed (len=0) bursts")

  assert(!tNasti.aw.valid || (tNasti.aw.bits.burst === NastiConstants.BURST_INCR)
    || ((tNasti.aw.bits.burst === NastiConstants.BURST_FIXED) && tNasti.aw.bits.len === 0.U),
    "Illegal aw request: memory model only supports incrementing or fixed (len=0) bursts")

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

  if (cfg.params.beatCounters) {
    val totalReadBeats = RegInit(0.U(32.W))
    val totalWriteBeats = RegInit(0.U(32.W))
    when(tNasti.r.fire){ totalReadBeats := totalReadBeats + 1.U }
    when(tNasti.w.fire){ totalWriteBeats := totalWriteBeats + 1.U }
    io.mmReg.totalReadBeats foreach { _ := totalReadBeats}
    io.mmReg.totalWriteBeats foreach { _ := totalWriteBeats }
  }

  /** Generates a histogram of n bins, one of which is incremented by the value of count each target-cycle.
    *
    * @param maximums
    *   The maximum value indexed by each bin. Seq(0,4,8) would produce three bins: [0], [1-4], [5-8]
    *
    * @params
    *   count The UInt used to select which bin should be incremented.
    */
  def bindOccupancyHistogram(maximums: Seq[Int], count: UInt): Seq[UInt] = {
    val bins               = Seq.fill(maximums.size) { RegInit(0.U(32.W)) }
    assert(count <= maximums.last.U, "Count exceeds maximum range of histogram.")
    val upperBoundChecks   = maximums.map { count <= _.U }
    val lowerBoundChecks   = true.B +: (upperBoundChecks.dropRight(1).map { !_ })
    val binIncrementEnable = lowerBoundChecks.zip(upperBoundChecks).map { case (gtLB, lteUB) => gtLB && lteUB }

    bins.zip(binIncrementEnable).foreach { case (bin, en) => bin := bin + en }
    bins
  }

  io.mmReg.readOutstandingHistogram  := bindOccupancyHistogram(io.mmReg.readBins, pendingReads.value)
  io.mmReg.writeOutstandingHistogram := bindOccupancyHistogram(io.mmReg.writeBins, pendingAWReq.value)
}

// A class of simple timing models that has independently programmable bounds on
// the number of reads and writes the model will accept.
//
// This is in contrast to more complex DRAM models that propogate backpressure
// from shared structures back to the AXI4 request channels.
abstract class SplitTransactionMMRegIO(cfg: BaseConfig)(implicit p: Parameters) extends MMRegIO(cfg) {
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

abstract class SplitTransactionModelIO(implicit p: Parameters)
    extends TimingModelIO()(p) {
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
  awQueue.io.enq.valid := nastiReq.aw.fire
  awQueue.io.deq.ready := newWReq
  assert(awQueue.io.enq.ready || !nastiReq.aw.fire,
    "AW queue in SplitTransaction timing model would overflow.")
}
