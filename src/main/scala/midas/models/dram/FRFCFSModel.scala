package midas
package models

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.util.GenericParameterizedBundle
import junctions._
import midas.widgets._

case class FirstReadyFCFSConfig(
    dramKey: DRAMOrganizationKey,
    baseParams: BaseParams)
  extends DRAMBaseConfig(baseParams) {

  def elaborate()(implicit p: Parameters): FirstReadyFCFSModel = Module(new FirstReadyFCFSModel(this))
}

class FirstReadyFCFSIO(cfg: FirstReadyFCFSConfig)(implicit p: Parameters)
    extends TimingModelIO(cfg)(p){
  val mmReg = new DRAMMMRegIO(cfg)
}

class FirstReadyFCFSModel(cfg: FirstReadyFCFSConfig)(implicit p: Parameters) extends TimingModel(cfg)(p)
    with HasDRAMMASConstants {

  import DRAMMasEnums._

  lazy val io = IO(new FirstReadyFCFSIO(cfg))
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

  transactionQueue.io.enqB.valid := pendingReads.io.inc
  transactionQueue.io.enqB.bits.xaction.id := tNasti.ar.bits.id
  transactionQueue.io.enqB.bits.xaction.len := tNasti.ar.bits.len
  transactionQueue.io.enqB.bits.xaction.isWrite := false.B
  transactionQueue.io.enqB.bits.bankAddr := io.mmReg.bankAddr.getSubAddr(tNasti.ar.bits.addr)
  transactionQueue.io.enqB.bits.bankAddrOH := UIntToOH(transactionQueue.io.enqB.bits.bankAddr)
  transactionQueue.io.enqB.bits.rowAddr := io.mmReg.rowAddr.getSubAddr(tNasti.ar.bits.addr)
  transactionQueue.io.enqB.bits.rankAddr := io.mmReg.rankAddr.getSubAddr(tNasti.ar.bits.addr)
  transactionQueue.io.enqB.bits.rankAddrOH := UIntToOH(transactionQueue.io.enqB.bits.rankAddr)

  // Trackers for controller-level structural hazards
  val cmdBusBusy = Module(new DownCounter((maxDRAMTimingBits)))
  cmdBusBusy.io.decr := true.B


  // Forward declared wires
  val selectedCmd = Wire(init = cmd_nop)
  val memReqDone = (selectedCmd === cmd_casr || selectedCmd === cmd_casw)

  // Trackers for DRAM timing violations
  val rankStateTrackers = Seq.fill(cfg.dramKey.maxRanks)(Module(new RankStateTracker(cfg.dramKey)))

  // Prevents closing a row before a CAS command has been issued for the ready entry
  // Instead of counting the number, we keep a bit to indicate presence
  // it is set on activation, enqueuing a new ready entry, and unset when a memreq kills the last
  // ready entry
  val bankHasReadyEntries = RegInit(Wire(Vec(cfg.dramKey.maxRanks * cfg.dramKey.maxBanks, false.B)))

  // State for the collapsing buffer of pending memory references
  val refList = Seq.fill(cfg.maxReads + cfg.maxWrites)(
     RegInit({val w = Wire(Valid(new MASEntry(refKey))); w.valid := false.B; w}))

  val newReference = Wire(Decoupled(new MASEntry(refKey)))
  newReference.valid := transactionQueue.io.deq.valid
  newReference.bits := transactionQueue.io.deq.bits

  // Mark that the new reference hits an open row buffer, in case it missed the broadcast
  val rowHitsInRank = Vec(rankStateTrackers map { tracker =>
    Vec(tracker.io.rank.banks map { _.isRowHit(newReference.bits)}).asUInt })

  transactionQueue.io.deq.ready := newReference.ready

  val refUpdates = CollapsingBuffer(refList, newReference) // Stateless

  // Selects the oldest candidate from all ready references that can legally request a CAS
  val columnArbiter =  Module(new Arbiter(refList.head.bits.cloneType, refList.size))

  def checkRankBankLegality(getField: CommandLegalBools => Bool)(masEntry: MASEntry): Bool = {
    val bankFields = rankStateTrackers map { rank => Vec(rank.io.rank.banks map getField).asUInt }
    val bankLegal = (Mux1H(masEntry.rankAddrOH, bankFields) & masEntry.bankAddrOH).orR
    val rankFields = Vec(rankStateTrackers map { rank => getField(rank.io.rank) }).asUInt
    val rankLegal = (masEntry.rankAddrOH & rankFields).orR
    rankLegal && bankLegal
  }

  def rankWantsRef(rankAddrOH: UInt): Bool =
    (rankAddrOH & (Vec(rankStateTrackers map { _.io.rank.wantREF }).asUInt)).orR


  val canLegallyCASR = checkRankBankLegality( _.canCASR ) _
  val canLegallyCASW = checkRankBankLegality(_.canCASW) _
  val canLegallyACT = checkRankBankLegality(_.canACT) _
  val canLegallyPRE = checkRankBankLegality(_.canPRE) _

  columnArbiter.io.in <> Vec(refList map { entry =>
      val candidate = V2D(entry)
      val canCASR = canLegallyCASR(entry.bits)
      val canCASW = canLegallyCASW(entry.bits)
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

  val cmdBank = Wire(UInt(cfg.dramKey.bankBits.W), init = preBank)
  val cmdBankOH = UIntToOH(cmdBank)
  val cmdRank = Wire(UInt(cfg.dramKey.rankBits.W), init = columnArbiter.io.out.bits.rankAddr)
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
    when (sel.fire()) { ref.valid := false.B }
    // If the entry is not killed, but shares the same open row as the killed reference, return true
    !sel.fire() && ref.valid && ref.bits.isReady &&
    cmdBank === ref.bits.bankAddr && cmdRank === ref.bits.rankAddr
  }

  val otherReadyEntries = entriesStillReady reduce { _ || _ }
  val casAutoPRE = Mux(io.mmReg.openPagePolicy, false.B, memReqDone && !otherReadyEntries)


  // Watchu kno about critical paths?
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
  newReference.bits.isReady := // 1) Row just opened or 2) already open && No Auto-PRE to that row
    selectedCmd === cmd_act && newRefAddrMatch ||
    (rowHitsInRank(newReference.bits.rankAddr) & newReference.bits.bankAddrOH).orR &&
    !(memReqDone && casAutoPRE && newRefAddrMatch)


  // Useful only for the open-page policy. In closed page policy, precharges
  // are always issued as part of auto-pre commands on in preperation for refresh.
  newReference.bits.mayPRE := // Last ready reference serviced or no other ready entries
    Mux(io.mmReg.openPagePolicy,
      newReference.bits.addrMatch(cmdRank, cmdBank) && memReqDone && !otherReadyEntries ||
      !bankHasReadyEntries(Cat(newReference.bits.rankAddr, newReference.bits.bankAddr)),
      false.B)

  // Check if the broadcasted cmdBank and cmdRank hit a ready entry
  when(memReqDone || selectedCmd === cmd_act) {
    bankHasReadyEntries(Cat(cmdRank, cmdBank)) := memReqDone && otherReadyEntries || selectedCmd === cmd_act
  }

  when (newReference.bits.isReady & newReference.fire()){
    bankHasReadyEntries(Cat(newReference.bits.rankAddr, newReference.bits.bankAddr)) := true.B
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

  cmdBusBusy.io.set.bits := timings.tCMD - 1.U
  cmdBusBusy.io.set.valid := selectedCmd != cmd_nop

  lazy val backend = Module(new SplitAXI4Backend(cfg))
  // Dequeue completed transactions in output queues
  // Read transactions use a latency pipe to account for tCAS
  val completedReads = Module(new DynamicLatencyPipe(
                         ReadMetaData(columnArbiter.io.out.bits.xaction),
                         entries = cfg.maxReads,
                         maxDRAMTimingBits))
  completedReads.io.enq.bits := ReadMetaData(columnArbiter.io.out.bits.xaction)
  completedReads.io.enq.valid := memReqDone && !columnArbiter.io.out.bits.xaction.isWrite
  completedReads.io.latency := timings.tCAS + timings.tAL
  completedReads.io.tCycle := tCycle
  backend.io.newRead <> completedReads.io.deq

  // For writes we send out the acknowledge immediately
  backend.io.newWrite.bits := WriteMetaData(columnArbiter.io.out.bits.xaction)
  backend.io.newWrite.valid := memReqDone && columnArbiter.io.out.bits.xaction.isWrite

  // Dump the cmd stream
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
