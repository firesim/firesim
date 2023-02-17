package midas
package models

import freechips.rocketchip.config.Parameters

import chisel3._
import chisel3.util._

import midas.widgets._


case class FirstReadyFCFSConfig(
    dramKey: DramOrganizationParams,
    schedulerWindowSize: Int,
    transactionQueueDepth: Int,
    backendKey: DRAMBackendKey = DRAMBackendKey(4, 4, DRAMMasEnums.backendLatencyBits),
    params: BaseParams)
  extends DRAMBaseConfig {

  def elaborate()(implicit p: Parameters): FirstReadyFCFSModel = Module(new FirstReadyFCFSModel(this))
}

class FirstReadyFCFSMMRegIO(val cfg: FirstReadyFCFSConfig) extends BaseDRAMMMRegIO(cfg) {
  val schedulerWindowSize = Input(UInt(log2Ceil(cfg.schedulerWindowSize).W))
  val transactionQueueDepth = Input(UInt(log2Ceil(cfg.transactionQueueDepth).W))

  val registers = dramBaseRegisters ++ Seq(
    (schedulerWindowSize -> RuntimeSetting(
        default =  cfg.schedulerWindowSize,
        query   = "Reference queue depth",
        min     = 1,
        max     = Some(cfg.schedulerWindowSize))),
    transactionQueueDepth -> RuntimeSetting(
        default = cfg.transactionQueueDepth,
        query   = "Transaction queue depth",
        min     = 1,
        max     = Some(cfg.transactionQueueDepth)))

  def requestSettings(): Unit = {
    Console.println(s"Configuring First-Ready First-Come First Serve Model")
    setBaseDRAMSettings()
  }
}

class FirstReadyFCFSIO(val cfg: FirstReadyFCFSConfig)(implicit p: Parameters) extends TimingModelIO()(p){
  val mmReg = new FirstReadyFCFSMMRegIO(cfg)
}

class FirstReadyFCFSModel(cfg: FirstReadyFCFSConfig)(implicit p: Parameters) extends TimingModel(cfg)(p)
    with HasDRAMMASConstants {

  val longName = "First-Ready FCFS MAS"
  def printTimingModelGenerationConfig: Unit = {}
  /**************************** CHISEL BEGINS *********************************/

  import DRAMMasEnums._
  lazy val io = IO(new FirstReadyFCFSIO(cfg))

  val timings = io.mmReg.dramTimings

  val backend = Module(new DRAMBackend(cfg.backendKey))
  val xactionScheduler = Module(new UnifiedFIFOXactionScheduler(cfg.transactionQueueDepth, cfg))
  xactionScheduler.io.req <> nastiReq
  xactionScheduler.io.pendingAWReq := pendingAWReq.value
  xactionScheduler.io.pendingWReq := pendingWReq.value

  // Trackers for controller-level structural hazards
  val cmdBusBusy = Module(new DownCounter((maxDRAMTimingBits)))
  cmdBusBusy.io.decr := true.B


  // Forward declared wires
  val selectedCmd = WireInit(cmd_nop)
  val memReqDone = (selectedCmd === cmd_casr || selectedCmd === cmd_casw)

  // Trackers for DRAM timing violations
  val rankStateTrackers = Seq.fill(cfg.dramKey.maxRanks)(Module(new RankStateTracker(cfg.dramKey)))

  // Prevents closing a row before a CAS command has been issued for the ready entry
  // Instead of counting the number, we keep a bit to indicate presence
  // it is set on activation, enqueuing a new ready entry, and unset when a memreq kills the last
  // ready entry
  val bankHasReadyEntries = RegInit(VecInit(Seq.fill(cfg.dramKey.maxRanks * cfg.dramKey.maxBanks)(false.B)))

  // State for the collapsing buffer of pending memory references
  val newReference = Wire(Decoupled(new FirstReadyFCFSEntry(cfg)))
  newReference.valid := xactionScheduler.io.nextXaction.valid
  newReference.bits.decode(xactionScheduler.io.nextXaction.bits, io.mmReg)

  // Mark that the new reference hits an open row buffer, in case it missed the broadcast
  val rowHitsInRank = VecInit(rankStateTrackers map { tracker =>
    VecInit(tracker.io.rank.banks map { _.isRowHit(newReference.bits)}).asUInt })

  xactionScheduler.io.nextXaction.ready := newReference.ready

  val refBuffer = CollapsingBuffer(
    enq               = newReference,
    depth             = cfg.schedulerWindowSize,
    programmableDepth = Some(io.mmReg.schedulerWindowSize)
  )
  val refList = refBuffer.io.entries
  val refUpdates = refBuffer.io.updates

  // Selects the oldest candidate from all ready references that can legally request a CAS
  val columnArbiter =  Module(new Arbiter(refList.head.bits.cloneType, refList.size))

  def checkRankBankLegality(getField: CommandLegalBools => Bool)(masEntry: FirstReadyFCFSEntry): Bool = {
    val bankFields = rankStateTrackers map { rank => VecInit(rank.io.rank.banks map getField).asUInt }
    val bankLegal = (Mux1H(masEntry.rankAddrOH, bankFields) & masEntry.bankAddrOH).orR
    val rankFields = VecInit(rankStateTrackers map { rank => getField(rank.io.rank) }).asUInt
    val rankLegal = (masEntry.rankAddrOH & rankFields).orR
    rankLegal && bankLegal
  }

  def rankWantsRef(rankAddrOH: UInt): Bool =
    (rankAddrOH & (VecInit(rankStateTrackers map { _.io.rank.wantREF }).asUInt)).orR


  val canLegallyCASR = checkRankBankLegality( _.canCASR ) _
  val canLegallyCASW = checkRankBankLegality(_.canCASW) _
  val canLegallyACT = checkRankBankLegality(_.canACT) _
  val canLegallyPRE = checkRankBankLegality(_.canPRE) _

  columnArbiter.io.in <> refList.map({ entry =>
      val candidate = V2D(entry)
      val canCASR = canLegallyCASR(entry.bits) && backend.io.newRead.ready
      val canCASW = canLegallyCASW(entry.bits) && backend.io.newWrite.ready
      candidate.valid := entry.valid && entry.bits.isReady &&
        Mux(entry.bits.xaction.isWrite, canCASW, canCASR) &&
        !rankWantsRef(entry.bits.rankAddrOH)

      candidate
    })


  val entryWantsPRE = refList map { ref => ref.valid && ref.bits.wantPRE() && canLegallyPRE(ref.bits) }
  val entryWantsACT = refList map { ref => ref.valid && ref.bits.wantACT() && canLegallyACT(ref.bits) &&
    !rankWantsRef(ref.bits.rankAddrOH) }

  val preBank = PriorityMux(entryWantsPRE, refList.map(_.bits.bankAddr))
  val preRank = PriorityMux(entryWantsPRE, refList.map(_.bits.rankAddr))
  val suggestPre = entryWantsPRE reduce {_ || _}

  val actRank = PriorityMux(entryWantsACT, refList.map(_.bits.rankAddr))
  val actBank = PriorityMux(entryWantsACT, refList.map(_.bits.bankAddr))
  val actRow = PriorityMux(entryWantsACT, refList.map(_.bits.rowAddr))

  // See if the oldest pending row reference wants a PRE an ACT
  val suggestAct = (entryWantsACT.zip(entryWantsPRE)).foldRight(false.B)({
    case ((act, pre), current) => Mux(act, true.B, !pre && current) })

  // NB: These are not driven for all command types. Ex. When issuing a CAS cmdRow
  // will not correspond to the row of the CAS command since that is implicit
  // to the state of the bank.
  val cmdBank = WireInit(UInt(cfg.dramKey.bankBits.W), init = preBank)
  val cmdBankOH = UIntToOH(cmdBank)
  val cmdRank = WireInit(UInt(cfg.dramKey.rankBits.W), init = columnArbiter.io.out.bits.rankAddr)
  val cmdRow = actRow

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
  }.elsewhen(columnArbiter.io.out.valid){
    selectedCmd := Mux(columnArbiter.io.out.bits.xaction.isWrite, cmd_casw, cmd_casr)
    cmdBank := columnArbiter.io.out.bits.bankAddr
  }.elsewhen(suggestAct) {
    selectedCmd := cmd_act
    cmdRank := actRank
    cmdBank := actBank
  }.elsewhen(suggestPre) {
    selectedCmd := cmd_pre
    cmdRank := preRank
  }

  // Remove a reference if it is granted a column access
  columnArbiter.io.out.ready := selectedCmd === cmd_casw || selectedCmd === cmd_casr

  // Take the readies from the arbiter, and kill the selected entry
  val entriesStillReady = refUpdates.zip(columnArbiter.io.in) map { case (ref, sel) =>
    when (sel.fire) { ref.valid := false.B }
    // If the entry is not killed, but shares the same open row as the killed reference, return true
    !sel.fire && ref.valid && ref.bits.isReady &&
    cmdBank === ref.bits.bankAddr && cmdRank === ref.bits.rankAddr
  }

  val otherReadyEntries = entriesStillReady reduce { _ || _ }
  val casAutoPRE = Mux(io.mmReg.openPagePolicy, false.B, memReqDone && !otherReadyEntries)

  // Mark new entries that now hit in a open row buffer
  // Or invalidate them if a precharge was issued
  refUpdates.foreach({ ref =>
    when(cmdRank === ref.bits.rankAddr && cmdBank === ref.bits.bankAddr) {
      when (selectedCmd === cmd_act) {
        ref.bits.isReady := ref.bits.rowAddr === cmdRow
        ref.bits.mayPRE := false.B
      }.elsewhen (selectedCmd === cmd_pre) {
        ref.bits.isReady := false.B
        ref.bits.mayPRE := false.B
      }.elsewhen (memReqDone && !otherReadyEntries) {
        ref.bits.mayPRE := true.B
      }
    }
  })

  val newRefAddrMatch = newReference.bits.addrMatch(cmdRank, cmdBank, Some(cmdRow))
  val newRefBankAddrMatch = newReference.bits.addrMatch(cmdRank, cmdBank)
  newReference.bits.isReady := // 1) Row just opened or 2) already open && No precharges to that row this cycle
    selectedCmd === cmd_act && newRefAddrMatch ||
    (rowHitsInRank(newReference.bits.rankAddr) & newReference.bits.bankAddrOH).orR &&
    !(memReqDone && casAutoPRE && newRefBankAddrMatch) && !(selectedCmd === cmd_pre && newRefBankAddrMatch)


  // Useful only for the open-page policy. In closed page policy, precharges
  // are always issued as part of auto-pre commands on in preperation for refresh.
  newReference.bits.mayPRE := // Last ready reference serviced or no other ready entries
    Mux(io.mmReg.openPagePolicy,
      // 1:The last ready request has been made to the bank
      newReference.bits.addrMatch(cmdRank, cmdBank) && memReqDone && !otherReadyEntries ||
      // 2: There are no ready references, and a precharge is not being issued to the bank this cycle
      !bankHasReadyEntries(Cat(newReference.bits.rankAddr, newReference.bits.bankAddr)) && 
      !(selectedCmd === cmd_pre && newRefBankAddrMatch),
      false.B)

  // Check if the broadcasted cmdBank and cmdRank hit a ready entry
  when(memReqDone || selectedCmd === cmd_act) {
    bankHasReadyEntries(Cat(cmdRank, cmdBank)) := memReqDone && otherReadyEntries || selectedCmd === cmd_act
  }

  when (newReference.bits.isReady & newReference.fire){
    bankHasReadyEntries(Cat(newReference.bits.rankAddr, newReference.bits.bankAddr)) := true.B
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

  cmdBusBusy.io.set.bits := timings.tCMD - 1.U
  cmdBusBusy.io.set.valid := selectedCmd =/= cmd_nop

  backend.io.tCycle := tCycle
  backend.io.newRead.bits  := ReadResponseMetaData(columnArbiter.io.out.bits.xaction)
  backend.io.newRead.valid := memReqDone && !columnArbiter.io.out.bits.xaction.isWrite
  backend.io.readLatency := timings.tCAS + timings.tAL + io.mmReg.backendLatency

  // For writes we send out the acknowledge immediately
  backend.io.newWrite.bits := WriteResponseMetaData(columnArbiter.io.out.bits.xaction)
  backend.io.newWrite.valid := memReqDone && columnArbiter.io.out.bits.xaction.isWrite
  backend.io.writeLatency := 1.U

  wResp <> backend.io.completedWrite
  rResp <> backend.io.completedRead

  // Dump the cmd stream
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
