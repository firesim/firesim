package midas
package models

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters


case class FIFOMASConfig(
    dramKey: DramOrganizationParams,
    transactionQueueDepth: Int,
    backendKey: DRAMBackendKey = DRAMBackendKey(4, 4, DRAMMasEnums.backendLatencyBits),
    params: BaseParams)
  extends DRAMBaseConfig {

  def elaborate()(implicit p: Parameters): FIFOMASModel = Module(new FIFOMASModel(this)(p))
}

class FIFOMASMMRegIO(val cfg: FIFOMASConfig) extends BaseDRAMMMRegIO(cfg) {
  val registers = dramBaseRegisters

  def requestSettings(): Unit = {
    Console.println(s"Configuring a First-Come First-Serve Model")
    setBaseDRAMSettings()
  }
}

class FIFOMASIO(val cfg: FIFOMASConfig)(implicit p: Parameters) extends TimingModelIO()(p) {
  val mmReg = new FIFOMASMMRegIO(cfg)
  //override def clonetype = new FIFOMASIO(cfg)(p).asInstanceOf[this.type]
}

class FIFOMASModel(cfg: FIFOMASConfig)(implicit p: Parameters) extends TimingModel(cfg)(p)
    with HasDRAMMASConstants {

  val longName = "FIFO MAS"
  def printTimingModelGenerationConfig: Unit = {}
  /**************************** CHISEL BEGINS *********************************/
  import DRAMMasEnums._

  lazy val io = IO(new FIFOMASIO(cfg))
  val timings = io.mmReg.dramTimings

  val backend = Module(new DRAMBackend(cfg.backendKey))
  val xactionScheduler = Module(new UnifiedFIFOXactionScheduler(cfg.transactionQueueDepth, cfg))
  xactionScheduler.io.req <> nastiReq
  xactionScheduler.io.pendingAWReq := pendingAWReq.value
  xactionScheduler.io.pendingWReq := pendingWReq.value

  val currentReference = Queue({
      val next =  Wire(Decoupled(new MASEntry(cfg)))
      next.valid := xactionScheduler.io.nextXaction.valid
      next.bits.decode(xactionScheduler.io.nextXaction.bits, io.mmReg)
      xactionScheduler.io.nextXaction.ready := next.ready
      next
    }, 1, pipe = true)

  val selectedCmd = WireInit(cmd_nop)
  val memReqDone = (selectedCmd === cmd_casr || selectedCmd === cmd_casw)

  // Trackers controller-level structural hazards
  val cmdBusBusy = Module(new DownCounter((maxDRAMTimingBits)))
  cmdBusBusy.io.decr := true.B

  // Trackers for bank-level hazards and timing violations
  val rankStateTrackers = Seq.fill(cfg.dramKey.maxRanks)(Module(new RankStateTracker(cfg.dramKey)))
  val currentRank = VecInit(rankStateTrackers map { _.io.rank })(currentReference.bits.rankAddr)
  val bankMuxes = VecInit(rankStateTrackers map { tracker => tracker.io.rank.banks(currentReference.bits.bankAddr) })
  val currentBank = WireInit(bankMuxes(currentReference.bits.rankAddr))

  // Command scheduling logic
  val cmdRow = currentReference.bits.rowAddr
  val cmdRank = WireInit(UInt(cfg.dramKey.rankBits.W), init = currentReference.bits.rankAddr)
  val cmdBank = WireInit(currentReference.bits.bankAddr)
  val cmdBankOH = UIntToOH(cmdBank)
  val currentRowHit = currentBank.state === bank_active && cmdRow === currentBank.openRow
  val casAutoPRE = WireInit(false.B)

  val canCASW = backend.io.newWrite.ready && currentReference.valid &&
    currentRowHit && currentReference.bits.xaction.isWrite && currentBank.canCASW &&
    currentRank.canCASW && !currentRank.wantREF

  val canCASR = backend.io.newRead.ready && currentReference.valid && currentRowHit &&
    !currentReference.bits.xaction.isWrite && currentBank.canCASR && currentRank.canCASR &&
    !currentRank.wantREF

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

  rankStateTrackers.zip(UIntToOH(cmdRank).asBools) foreach { case (state, cmdUsesThisRank)  =>
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
  cmdBusBusy.io.set.valid := (selectedCmd =/= cmd_nop)

  currentReference.ready := memReqDone

  backend.io.tCycle := tCycle
  backend.io.newRead.bits  := ReadResponseMetaData(currentReference.bits.xaction)
  backend.io.newRead.valid := memReqDone && !currentReference.bits.xaction.isWrite
  backend.io.readLatency := timings.tCAS + timings.tAL + io.mmReg.backendLatency

  // For writes we send out the acknowledge immediately
  backend.io.newWrite.bits := WriteResponseMetaData(currentReference.bits.xaction)
  backend.io.newWrite.valid := memReqDone && currentReference.bits.xaction.isWrite
  backend.io.writeLatency := 1.U

  wResp <> backend.io.completedWrite
  rResp <> backend.io.completedRead

  // Dump the command stream
  val cmdMonitor = Module(new CommandBusMonitor())
  cmdMonitor.io.cmd := selectedCmd
  cmdMonitor.io.rank := cmdRank
  cmdMonitor.io.bank := cmdBank
  cmdMonitor.io.row := cmdRow
  cmdMonitor.io.autoPRE := casAutoPRE

  val powerStats = (rankStateTrackers).zip(UIntToOH(cmdRank).asBools) map {
    case (rankState, cmdUsesThisRank) =>
      val powerMonitor = Module(new RankPowerMonitor(cfg.dramKey))
      powerMonitor.io.selectedCmd := selectedCmd
      powerMonitor.io.cmdUsesThisRank := cmdUsesThisRank
      powerMonitor.io.rankState := rankState.io.rank
      powerMonitor.io.stats
    }

  io.mmReg.rankPower := VecInit(powerStats)
}
