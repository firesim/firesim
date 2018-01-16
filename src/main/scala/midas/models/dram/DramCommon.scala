package midas
package models

import freechips.rocketchip.util.GenericParameterizedBundle
import chisel3._
import chisel3.util._

trait HasDRAMMASConstants {
  val maxDRAMTimingBits = 7 // width of a DRAM timing
  val tREFIWidth = 14       // Refresh interval. Suffices up to tCK = ~0.5ns (for 64ms, 8192 refresh commands)
  val tREFIBits = 14       // Refresh interval. Suffices up to tCK = ~0.5ns (for 64ms, 8192 refresh commands)
  val tRFCBits = 10       // Refresh interval. Suffices up to tCK = ~0.5ns (for 64ms, 8192 refresh commands)
  val numBankStates = 2
  val numRankStates = 2
}

object DRAMMasEnums extends HasDRAMMASConstants {
  val cmd_nop :: cmd_act :: cmd_pre :: cmd_casw ::  cmd_casr :: cmd_ref :: Nil = Enum(6)
  val bank_idle :: bank_active :: Nil = Enum(numBankStates)
  val rank_active :: rank_refresh :: Nil = Enum(numRankStates)
}

class DRAMProgrammableTimings extends Bundle with HasDRAMMASConstants {
  // The most vanilla of DRAM timings
  val tAL = UInt(maxDRAMTimingBits.W)
  val tCAS = UInt(maxDRAMTimingBits.W)
  val tCMD = UInt(maxDRAMTimingBits.W)
  val tCWD = UInt(maxDRAMTimingBits.W)
  val tCCD = UInt(maxDRAMTimingBits.W)
  val tFAW = UInt(maxDRAMTimingBits.W)
  val tRAS = UInt(maxDRAMTimingBits.W)
  val tREFI = UInt(tREFIBits.W)
  val tRC  = UInt(maxDRAMTimingBits.W)
  val tRCD = UInt(maxDRAMTimingBits.W)
  val tRFC = UInt(tRFCBits.W)
  val tRRD = UInt(maxDRAMTimingBits.W)
  val tRP  = UInt(maxDRAMTimingBits.W)
  val tRTP = UInt(maxDRAMTimingBits.W)
  val tRTRS = UInt(maxDRAMTimingBits.W)
  val tWR  = UInt(maxDRAMTimingBits.W)
  val tWTR = UInt(maxDRAMTimingBits.W)

  override def cloneType = new DRAMProgrammableTimings().asInstanceOf[this.type]
}

abstract class DRAMBaseConfig( baseParams: BaseParams) extends BaseConfig(baseParams) { 
  def dramKey: DRAMOrganizationKey
}

class DRAMMMRegIO(cfg: DRAMBaseConfig) extends MMRegIO(cfg) {
  // Addr assignment
  val bankAddr = Input(new ProgrammableSubAddr(cfg.dramKey.bankBits))
  val rowAddr = Input(new ProgrammableSubAddr(cfg.dramKey.rowBits))
  val rankAddr = Input(new ProgrammableSubAddr(cfg.dramKey.rankBits))
  // Page policy 1 = open, 0 = closed
  val openPagePolicy = Input(Bool())
  // Counts the number of misses in the open row buffer
  //val rowMisses = Output(UInt(32.W))
  val dramTimings = Input(new DRAMProgrammableTimings())
  val rankPower = Output(Vec(cfg.dramKey.maxRanks, new RankPowerIO))
}

case class DRAMOrganizationKey(maxBanks: Int, maxRanks: Int, maxRows: Int) {
  def bankBits = log2Up(maxBanks)
  def rankBits = log2Up(maxRanks)
  def rowBits = log2Up(maxRows)
}

trait CommandLegalBools {
  val canCASW = Output(Bool())
  val canCASR = Output(Bool())
  val canPRE  = Output(Bool())
  val canACT  = Output(Bool())
}

trait HasLegalityUpdateIO {
  val key: DRAMOrganizationKey
  import DRAMMasEnums._
  val timings = Input(new DRAMProgrammableTimings)
  val selectedCmd = Input(cmd_nop.cloneType)
  val autoPRE = Input(Bool())
  val cmdRow = Input(UInt(key.rowBits.W))
  val burstLength = Input(UInt(4.W)) // TODO: Fixme
}

case class BankReferenceKey(
    idBits: Int,
    lenBits: Int,
    dramKey: DRAMOrganizationKey
  ) extends HasTransactionMetaData

// Add some scheduler specific metadata to a reference
// TODO factor out different MAS metadata into a mixin
class MASEntry(key: BankReferenceKey) extends GenericParameterizedBundle(key) {
  val xaction = new TransactionMetaData(MetaDataWidths(key.idBits, key.lenBits))
  val rowAddr = UInt(key.dramKey.rowBits.W)
  val bankAddrOH = UInt(key.dramKey.maxBanks.W)
  val bankAddr = UInt(key.dramKey.bankBits.W)
  val rankAddrOH = UInt(key.dramKey.maxRanks.W)
  val rankAddr = UInt(key.dramKey.rankBits.W)
  val isReady = Bool() //Set when entry hits in open row buffer
  val mayPRE = Bool() // Set when no other entires hit open row buffer

  // We only ask for a precharge, if we have permission (no other references hit)
  // and the entry isn't personally ready
  def wantPRE(): Bool = !isReady && mayPRE // Don't need the dummy args
  def wantACT(): Bool = !isReady

  def addrMatch(rank: UInt, bank: UInt, row: Option[UInt] = None): Bool = {
    val rowHit =  row.foldLeft(true.B)({ case (p, addr) => p && addr === rowAddr })
    rank === rankAddr && bank === bankAddr && rowHit
  }
}
// Tracks the state of a bank, including:
//   - Whether it's active/idle
//   - Open row address
//   - Whether CAS, PRE, and ACT commands can be legally issued
//
// A MAS model uses these trackers to filte out illegal instructions for this bank
//
// A necessary condition for the controller to issue a CMD that uses this bank
// is that the can{CMD} bit be high. The controller of course all extra-bank
// timing and resource constraints are met. The controller must also ensure CAS
// commands use the open ROW. 

class BankStateTrackerO(key: DRAMOrganizationKey) extends GenericParameterizedBundle(key)
    with CommandLegalBools {

  import DRAMMasEnums._
  val openRow = Output(UInt(key.rowBits.W))
  val state = Output(Bool())

  def isRowHit(ref: MASEntry): Bool = ref.rowAddr === openRow && state === bank_active
}

class BankStateTrackerIO(val key: DRAMOrganizationKey) extends GenericParameterizedBundle(key)
  with HasLegalityUpdateIO {
  val out = new BankStateTrackerO(key)
  val cmdUsesThisBank = Input(Bool())
}

class BankStateTracker(key: DRAMOrganizationKey) extends Module with HasDRAMMASConstants {
  import DRAMMasEnums._
  val io = IO(new BankStateTrackerIO(key))

  val state = RegInit(bank_idle)
  val openRowAddr = Reg(UInt(key.rowBits.W))

  val nextLegalPRE = Module(new DownCounter(maxDRAMTimingBits))
  val nextLegalACT = Module(new DownCounter(maxDRAMTimingBits))
  val nextLegalCAS = Module(new DownCounter(maxDRAMTimingBits))

  Seq(nextLegalPRE, nextLegalCAS, nextLegalACT) foreach { mod =>
    mod.io.decr := true.B
    mod.io.set.valid := false.B
  }

  when (io.cmdUsesThisBank) {
    switch(io.selectedCmd) {
      is(cmd_act) {
        assert(io.out.canACT, "Bank Timing Violation: Controller issued activate command illegally")
        state := bank_active
        openRowAddr := io.cmdRow
        nextLegalCAS.io.set.valid := true.B
        nextLegalCAS.io.set.bits := io.timings.tRCD - io.timings.tAL - 1.U
        nextLegalPRE.io.set.valid := true.B
        nextLegalPRE.io.set.bits := io.timings.tRAS - 1.U
        nextLegalACT.io.set.valid := true.B
        nextLegalACT.io.set.bits := io.timings.tRC - 1.U
      }
      is(cmd_casr) {
        assert(io.out.canCASR, "Bank Timing Violation: Controller issued CASR command illegally")
        when (io.autoPRE) {
          state := bank_idle
          nextLegalACT.io.set.valid := true.B
          nextLegalACT.io.set.bits := io.timings.tRTP + io.timings.tAL + io.timings.tRP - 1.U
        }.otherwise {
          nextLegalPRE.io.set.valid := true.B
          nextLegalPRE.io.set.bits := io.timings.tRTP + io.timings.tAL - 1.U
        }
      }
      is(cmd_casw) {
        assert(io.out.canCASW, "Bank Timing Violation: Controller issued CASW command illegally")
        when (io.autoPRE) {
          state := bank_idle
          nextLegalACT.io.set.valid := true.B
          nextLegalACT.io.set.bits := io.timings.tCWD + io.timings.tAL + io.timings.tWR +
            io.timings.tCCD + io.timings.tRP + 1.U
        }.otherwise {
          nextLegalPRE.io.set.valid := true.B
          nextLegalPRE.io.set.bits := io.timings.tCWD + io.timings.tAL +  io.timings.tWR +
            io.timings.tCCD - 1.U
        }
      }
      is(cmd_pre) {
        assert(io.out.canPRE, "Bank Timing Violation: Controller issued PRE command illegally")
        state := bank_idle
        nextLegalACT.io.set.valid := true.B
        nextLegalACT.io.set.bits := io.timings.tRP - 1.U
      }
    }
  }

  io.out.canCASW := (state === bank_active) && nextLegalCAS.io.idle // Controller must check rowAddr
  io.out.canCASR := (state === bank_active) && nextLegalCAS.io.idle // Controller must check rowAddr
  io.out.canPRE := (state === bank_active) && nextLegalPRE.io.idle
  io.out.canACT := (state === bank_idle) && nextLegalACT.io.idle
  io.out.state := state
  io.out.openRow := openRowAddr
}


// Tracks the state of a rank, including:
//   - Whether CAS, PRE, and ACT commands can be legally issued
//
// A MAS model uses these trackers to filte out illegal instructions for this bank
//
// A necessary condition for the controller to issue a CMD that uses this bank
// is that the can{CMD} bit be high. The controller of course all extra-bank
// timing and resource constraints are met. The controller must also ensure CAS
// commands use the open ROW. 

class RankStateTrackerO(key: DRAMOrganizationKey) extends GenericParameterizedBundle(key)
    with CommandLegalBools {
  import DRAMMasEnums._
  val canREF = Output(Bool())
  val wantREF = Output(Bool())
  val state = Output(rank_active.cloneType)
  val banks = Vec(key.maxBanks, Output(new BankStateTrackerO(key)))
}

class RankStateTrackerIO(val key: DRAMOrganizationKey) extends GenericParameterizedBundle(key)
    with HasLegalityUpdateIO with HasDRAMMASConstants {
  val rank = new RankStateTrackerO(key)
  val tCycle = Input(UInt(maxDRAMTimingBits.W))
  val cmdUsesThisRank = Input(Bool())
  val cmdBankOH = Input(UInt(key.maxBanks.W))
}

class RankStateTracker(key: DRAMOrganizationKey) extends Module with HasDRAMMASConstants {
  import DRAMMasEnums._

  val io = IO(new RankStateTrackerIO(key))

  val nextLegalPRE = Module(new DownCounter(maxDRAMTimingBits))
  val nextLegalACT = Module(new DownCounter(tRFCBits))
  val nextLegalCASR = Module(new DownCounter(maxDRAMTimingBits))
  val nextLegalCASW = Module(new DownCounter(maxDRAMTimingBits))
  val tREFI = RegInit(0.U(tREFIBits.W))
  val state = RegInit(rank_active)
  val wantREF = RegInit(false.B)

  Seq(nextLegalPRE, nextLegalCASW, nextLegalCASR, nextLegalACT) foreach { mod =>
    mod.io.decr := true.B
    mod.io.set.valid := false.B
  }

  val tFAWcheck = Module(new Queue(io.tCycle.cloneType, entries = 4))
  tFAWcheck.io.enq.valid := io.cmdUsesThisRank && io.selectedCmd === cmd_act
  tFAWcheck.io.enq.bits := io.tCycle + io.timings.tFAW
  tFAWcheck.io.deq.ready := io.tCycle === tFAWcheck.io.deq.bits

  when (io.cmdUsesThisRank && io.selectedCmd === cmd_act) {
    assert(io.rank.canACT, "Rank Timing Violation: Controller issued ACT command illegally")
    nextLegalACT.io.set.valid := true.B
    nextLegalACT.io.set.bits := io.timings.tRRD - 1.U

  }.elsewhen (io.selectedCmd === cmd_casr) {
    assert(!io.cmdUsesThisRank || io.rank.canCASR,
      "Rank Timing Violation: Controller issued CASR command illegally")
    nextLegalCASR.io.set.valid := true.B
    nextLegalCASR.io.set.bits := io.timings.tCCD +
      Mux(io.cmdUsesThisRank, 0.U, io.timings.tRTRS) - 1.U

    // TODO: tRTRS isn't the correct parameter here, but need a two cycle delay in DDR3
    nextLegalCASW.io.set.valid := true.B
    nextLegalCASW.io.set.bits := io.timings.tCAS + io.timings.tCCD - io.timings.tCWD +
      io.timings.tRTRS - 1.U

  }.elsewhen (io.selectedCmd === cmd_casw) {
    assert(!io.cmdUsesThisRank || io.rank.canCASW,
      "Rank Timing Violation: Controller issued CASW command illegally")
    nextLegalCASR.io.set.valid := true.B
    nextLegalCASR.io.set.bits := Mux(io.cmdUsesThisRank,
      io.timings.tCWD + io.timings.tCCD + io.timings.tWTR - 1.U,
      io.timings.tCWD + io.timings.tCCD + io.timings.tRTRS - io.timings.tCAS - 1.U)

    // TODO: OST
    nextLegalCASW.io.set.valid := true.B
    nextLegalCASW.io.set.bits := io.timings.tCCD - 1.U

  }.elsewhen (io.cmdUsesThisRank && io.selectedCmd === cmd_pre) {
      assert(io.rank.canPRE, "Rank Timing Violation: Controller issued PRE command illegally")

  }.elsewhen (io.cmdUsesThisRank && io.selectedCmd === cmd_ref) {
      assert(io.rank.canREF, "Rank Timing Violation: Controller issued REF command illegally")
      wantREF := false.B
      state := rank_refresh
      nextLegalACT.io.set.valid := true.B
      nextLegalACT.io.set.bits := io.timings.tRFC - 1.U
  }

  when (tREFI === io.timings.tREFI) {
    tREFI := 0.U
    wantREF := true.B
  }.otherwise {
    tREFI := tREFI + 1.U
  }

  when (state === rank_refresh && nextLegalACT.io.current === 1.U) {
    state := rank_active
  }

  val bankTrackers = Seq.fill(key.maxBanks)(Module(new BankStateTracker(key)).io)
  io.rank.banks.zip(bankTrackers) foreach { case (out, bank) => out := bank.out }

  bankTrackers.zip(io.cmdBankOH.toBools) foreach { case (bank, cmdUsesThisBank)  =>
    bank.timings := io.timings
    bank.selectedCmd := io.selectedCmd
    bank.cmdUsesThisBank := cmdUsesThisBank && io.cmdUsesThisRank
    bank.cmdRow := io.cmdRow
    bank.autoPRE:= io.autoPRE
  }

  io.rank.canREF := (bankTrackers map {  _.out.canACT } reduce { _ && _ })
  io.rank.canCASR := nextLegalCASR.io.idle
  io.rank.canCASW := nextLegalCASW.io.idle
  io.rank.canPRE := nextLegalPRE.io.idle
  io.rank.canACT := nextLegalACT.io.idle && tFAWcheck.io.enq.ready
  io.rank.wantREF := wantREF
  io.rank.state := state
}


class CommandBusMonitor extends Module {
  import DRAMMasEnums._
  val io = IO( new Bundle {
    val cmd = Input(cmd_nop.cloneType)
    val rank = Input(UInt())
    val bank = Input(UInt())
    val row = Input(UInt())
    val autoPRE = Input(Bool())
  })

  val cycleCounter = RegInit(1.U(32.W))
  val lastCommand = RegInit(0.U(32.W))
  cycleCounter := cycleCounter + 1.U
  when (io.cmd != cmd_nop) {
    lastCommand := cycleCounter
    when (lastCommand + 1.U != cycleCounter) { printf("nop(%d);\n", cycleCounter - lastCommand - 1.U) }
  }

  switch (io.cmd) {
    is(cmd_act) {
      printf("activate(%d, %d, %d); // %d\n", io.rank, io.bank, io.row, cycleCounter)
    }
    is(cmd_casr) {
      val autoPRE = io.autoPRE
      val burstChop = false.B
      val column = 0.U // Don't care since we aren't checking data
      printf("read(%d, %d, %d, %x, %x); // %d\n",
        io.rank, io.bank, column, autoPRE, burstChop, cycleCounter)
    }
    is(cmd_casw) {
      val autoPRE = io.autoPRE
      val burstChop = false.B
      val column = 0.U // Don't care since we aren't checking data
      val mask = 0.U // Don't care since we aren't checking data
      val data = 0.U // Don't care since we aren't checking data
      printf("write(%d, %d, %d, %x, %x, %d, %d); // %d\n",
        io.rank, io.bank, column, autoPRE, burstChop, mask, data, cycleCounter)
    }
    is(cmd_ref) {
      printf("refresh(%d); // %d\n", io.rank, cycleCounter)
    }
    is(cmd_pre) {
      val preAll = false.B
      printf("precharge(%d,%d,%d); // %d\n",io.rank, io.bank, preAll, cycleCounter)
    }
  }
}

class RankRefreshUnitIO(key: DRAMOrganizationKey) extends GenericParameterizedBundle(key) {
  val rankStati = Vec(key.maxRanks, Flipped(new RankStateTrackerO(key)))
  // The user may have instantiated multiple ranks, but is only modelling a single
  // rank system. Don't issue refreshes to ranks we aren't modelling
  val ranksInUse = Input(UInt(key.maxRanks.W))
  val suggestREF = Output(Bool())
  val refRankAddr = Output(UInt(key.rankBits.W))
  val suggestPRE = Output(Bool())
  val preRankAddr = Output(UInt(key.rankBits.W))
  val preBankAddr = Output(UInt(key.bankBits.W))
}

class RefreshUnit(key: DRAMOrganizationKey) extends Module {
  val io = IO(new RankRefreshUnitIO(key))

  val ranksWantingRefresh = Vec(io.rankStati map { _.wantREF }).asUInt
  val refreshableRanks = Vec(io.rankStati map { _.canREF }).asUInt & io.ranksInUse

  io.refRankAddr := PriorityEncoder(ranksWantingRefresh & refreshableRanks)
  io.suggestREF := (ranksWantingRefresh & refreshableRanks).orR

  // preRef => a precharge needed before refresh may occur
  val preRefBanks = io.rankStati map { rank => PriorityEncoder(rank.banks map { _.canPRE })}

  val prechargeableRanks = Vec(io.rankStati map { rank => rank.canPRE &&
    (rank.banks map { _.canPRE } reduce { _ || _ })}).asUInt & io.ranksInUse

  io.suggestPRE := (ranksWantingRefresh & prechargeableRanks).orR
  io.preRankAddr := PriorityEncoder(ranksWantingRefresh & prechargeableRanks)
  io.preBankAddr := PriorityMux(ranksWantingRefresh & prechargeableRanks, preRefBanks)
}


// Outputs for counters used to feed to micron's power calculator
// # CASR, CASW is a proxy for cycles of read and write data (assuming fixed burst length)
// 1 -  (ACT/(CASR + CASW)) = rank row buffer hit rate
class RankPowerIO extends Bundle {
  val allPreCycles = UInt(32.W) // # of cycles the rank has all banks precharged
  val numCASR = UInt(32.W) // Assume no burst-chop
  val numCASW = UInt(32.W) // Ditto above
  val numACT = UInt(32.W)

  // TODO
  // CKE low & all banks pre
  // CKE low & at least one bank active
}

object RankPowerIO {
  def apply(): RankPowerIO = {
    val w = Wire(new RankPowerIO())
    w.allPreCycles := 0.U
    w.numCASR := 0.U
    w.numCASR := 0.U
    w.numACT := 0.U
    w
  }
}

class RankPowerMonitor(key: DRAMOrganizationKey) extends Module with HasDRAMMASConstants {
  import DRAMMasEnums._
  val io = IO(new Bundle {
    val stats = Output(new RankPowerIO)
    val rankState = Input(new RankStateTrackerO(key))
    val selectedCmd = Input(cmd_nop.cloneType)
    val cmdUsesThisRank = Input(Bool())
  })
  val stats = RegInit(RankPowerIO())

  when (io.cmdUsesThisRank) {
    switch(io.selectedCmd) {
      is(cmd_act) {
        stats.numACT := stats.numACT + 1.U
      }
      is(cmd_casw) {
        stats.numCASW := stats.numCASW + 1.U
      }
      is(cmd_casr) {
        stats.numCASR := stats.numCASR + 1.U
      }
    }
  }

  // This is questionable. Needs to be reevaluated once CKE toggling is accounted for
  when (io.rankState.state != rank_refresh && ((io.rankState.banks) forall { _.canACT })) {
    stats.allPreCycles := stats.allPreCycles + 1.U
  }

  io.stats := stats
}
