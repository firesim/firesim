package midas
package models

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import junctions._
import midas.widgets._

case class FIFOMASConfig(
    dramKey: DRAMOrganizationKey,
    baseParams: BaseParams)
  extends DRAMBaseConfig(baseParams) {

  def elaborate()(implicit p: Parameters): FIFOMASModel = Module(new FIFOMASModel(this))
}

class FIFOMASIO(cfg: FIFOMASConfig)(implicit p: Parameters) extends TimingModelIO(cfg)(p) {
  val mmReg = new DRAMMMRegIO(cfg)
}

class FIFOMASModel(cfg: FIFOMASConfig)(implicit p: Parameters) extends TimingModel(cfg)(p)
    with HasDRAMMASConstants {

  import DRAMMasEnums._

  lazy val io = IO(new FIFOMASIO(cfg))
  val timings = io.mmReg.dramTimings
  val refKey = BankReferenceKey(nastiXIdBits, nastiXLenBits, cfg.dramKey)

  val transactionQueue = Module(new DualQueue(
      gen = new MASEntry(refKey),
      entries = cfg.maxWrites + cfg.maxReads))

  transactionQueue.io.enqA.valid := newWReq
  transactionQueue.io.enqA.bits.xaction.id := awQueue.io.deq.bits.id
  transactionQueue.io.enqA.bits.xaction.len := awQueue.io.deq.bits.len
  transactionQueue.io.enqA.bits.xaction.isWrite := true.B
  transactionQueue.io.enqA.bits.bankAddr := io.mmReg.bankAddr.getSubAddr(awQueue.io.deq.bits.addr)
  transactionQueue.io.enqA.bits.bankAddrOH := UIntToOH(transactionQueue.io.enqA.bits.bankAddr)
  transactionQueue.io.enqA.bits.rowAddr := io.mmReg.rowAddr.getSubAddr(awQueue.io.deq.bits.addr)
  transactionQueue.io.enqA.bits.rankAddr := io.mmReg.rankAddr.getSubAddr(awQueue.io.deq.bits.addr)
  transactionQueue.io.enqA.bits.rankAddrOH := UIntToOH(transactionQueue.io.enqA.bits.rankAddr)

  transactionQueue.io.enqB.valid := tNasti.ar.fire
  transactionQueue.io.enqB.bits.xaction.id := tNasti.ar.bits.id
  transactionQueue.io.enqB.bits.xaction.len := tNasti.ar.bits.len
  transactionQueue.io.enqB.bits.xaction.isWrite := false.B
  transactionQueue.io.enqB.bits.bankAddr := io.mmReg.bankAddr.getSubAddr(tNasti.ar.bits.addr)
  transactionQueue.io.enqB.bits.bankAddrOH := UIntToOH(transactionQueue.io.enqB.bits.bankAddr)
  transactionQueue.io.enqB.bits.rowAddr := io.mmReg.rowAddr.getSubAddr(tNasti.ar.bits.addr)
  transactionQueue.io.enqB.bits.rankAddr := io.mmReg.rankAddr.getSubAddr(tNasti.ar.bits.addr)
  transactionQueue.io.enqB.bits.rankAddrOH := UIntToOH(transactionQueue.io.enqB.bits.rankAddr)

  val currentReference = transactionQueue.io.deq

  val selectedCmd = Wire(init = cmd_nop)
  val memReqDone = (selectedCmd === cmd_casr || selectedCmd === cmd_casw)

  // Trackers controller-level structural hazards
  val cmdBusBusy = Module(new DownCounter((maxDRAMTimingBits)))
  cmdBusBusy.io.decr := true.B

  // Trackers for bank-level hazards and timing violations
  val rankStateTrackers = Seq.fill(cfg.dramKey.maxRanks)(Module(new RankStateTracker(cfg.dramKey)))
  val currentRank = Vec(rankStateTrackers map { _.io.rank })(currentReference.bits.rankAddr)
  val bankMuxes = Vec(rankStateTrackers map { tracker => tracker.io.rank.banks(currentReference.bits.bankAddr) })
  val currentBank = Wire(init = bankMuxes(currentReference.bits.rankAddr))

  // Command scheduling logic
  val cmdRow = currentReference.bits.rowAddr
  val cmdRank = Wire(UInt(cfg.dramKey.rankBits.W), init = transactionQueue.io.deq.bits.rankAddr)
  val cmdBank = Wire(init = currentReference.bits.bankAddr)
  val cmdBankOH = UIntToOH(cmdBank)
  val currentRowHit = currentBank.state === bank_active && cmdRow === currentBank.openRow
  val casAutoPRE = Wire(init = false.B)

  val canCASW = currentReference.valid && currentRowHit && currentReference.bits.xaction.isWrite &&
    currentBank.canCASW && currentRank.canCASW && !currentRank.wantREF

  val canCASR = currentReference.valid && currentRowHit && !currentReference.bits.xaction.isWrite &&
    currentBank.canCASR && currentRank.canCASR && !currentRank.wantREF

  val refreshUnit = Module(new RefreshUnit(cfg.dramKey)).io
  refreshUnit.ranksInUse := io.mmReg.rankAddr.maskToOH()
  refreshUnit.rankStati.zip(rankStateTrackers) foreach { case (refInput, tracker) =>
    refInput := tracker.io.rank }

  when (refreshUnit.suggestREF) {
    selectedCmd := cmd_ref
    cmdRank := refreshUnit.refRankAddr
  }.elsewhen (refreshUnit.suggestPRE) {
    selectedCmd := cmd_pre
    cmdRank := refreshUnit.preRankAddr
    cmdBank := refreshUnit.preBankAddr
  }.elsewhen(io.mmReg.openPagePolicy) {
    when (canCASR) {
      selectedCmd := cmd_casr
    }.elsewhen (canCASW) {
      selectedCmd := cmd_casw
    }.elsewhen (currentReference.valid && currentBank.canACT && currentRank.canACT && !currentRank.wantREF) {
      selectedCmd := cmd_act
    }.elsewhen (currentReference.valid && !currentRowHit && currentBank.canPRE && currentRank.canPRE) {
      selectedCmd := cmd_pre
    }
  }.otherwise {
    when (canCASR) {
      selectedCmd := cmd_casr
      casAutoPRE := true.B
    }.elsewhen (canCASW) {
      selectedCmd := cmd_casw
      casAutoPRE := true.B
    }.elsewhen (currentReference.valid && currentBank.canACT && currentRank.canACT && !currentRank.wantREF) {
      selectedCmd := cmd_act
    }
  }

  rankStateTrackers.zip(UIntToOH(cmdRank).toBools) foreach { case (state, cmdUsesThisRank)  =>
    state.io.selectedCmd := selectedCmd
    state.io.cmdBankOH := cmdBankOH
    state.io.cmdRow := cmdRow
    state.io.autoPRE := casAutoPRE
    state.io.cmdUsesThisRank := cmdUsesThisRank
    state.io.timings := timings
    state.io.tCycle := tCycle
  }

  // TODO: sensible mapping to DRAM bus width

  cmdBusBusy.io.set.bits := timings.tCMD - 1.U
  cmdBusBusy.io.set.valid := (selectedCmd != cmd_nop)

  transactionQueue.io.deq.ready := memReqDone

  lazy val backend = Module(new SplitAXI4Backend(cfg))
  // Dequeue completed transactions in output queues
  // Read transactions use a latency pipe to account for tCAS
  val completedReads = Module(new DynamicLatencyPipe(
                         ReadMetaData(transactionQueue.io.deq.bits.xaction),
                         entries = cfg.maxReads,
                         maxDRAMTimingBits))
  completedReads.io.enq.bits := ReadMetaData(transactionQueue.io.deq.bits.xaction)
  completedReads.io.enq.valid := memReqDone && !transactionQueue.io.deq.bits.xaction.isWrite
  completedReads.io.latency := timings.tCAS + timings.tAL
  completedReads.io.tCycle := tCycle
  backend.io.newRead <> completedReads.io.deq

  // For writes we send out the acknowledge immediately
  backend.io.newWrite.bits := WriteMetaData(transactionQueue.io.deq.bits.xaction)
  backend.io.newWrite.valid := memReqDone && transactionQueue.io.deq.bits.xaction.isWrite

  // Dump the command stream
  val cmdMonitor = Module(new CommandBusMonitor())
  cmdMonitor.io.cmd := selectedCmd
  cmdMonitor.io.rank := cmdRank
  cmdMonitor.io.bank := cmdBank
  cmdMonitor.io.row := cmdRow
  cmdMonitor.io.autoPRE := casAutoPRE

  val powerStats = (rankStateTrackers).zip(UIntToOH(cmdRank).toBools) map {
    case (rankState, cmdUsesThisRank) =>
      val powerMonitor = Module(new RankPowerMonitor(cfg.dramKey))
      powerMonitor.io.selectedCmd := selectedCmd
      powerMonitor.io.cmdUsesThisRank := cmdUsesThisRank
      powerMonitor.io.rankState := rankState.io.rank
      powerMonitor.io.stats
    }

  io.mmReg.rankPower := Vec(powerStats)
}
