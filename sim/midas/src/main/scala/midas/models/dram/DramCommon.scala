package midas
package models

import freechips.rocketchip.config.Parameters
import freechips.rocketchip.util.GenericParameterizedBundle
import chisel3._
import chisel3.util._

import org.json4s._
import org.json4s.native.JsonMethods._

import Console.{UNDERLINED, GREEN, RESET}
import scala.io.Source


trait HasDRAMMASConstants {
  val maxDRAMTimingBits = 7 // width of a DRAM timing
  val tREFIWidth = 14       // Refresh interval. Suffices up to tCK = ~0.5ns (for 64ms, 8192 refresh commands)
  val tREFIBits = 14       // Refresh interval. Suffices up to tCK = ~0.5ns (for 64ms, 8192 refresh commands)
  val tRFCBits = 10
  val backendLatencyBits = 12
  val numBankStates = 2
  val numRankStates = 2
}

object DRAMMasEnums extends HasDRAMMASConstants {
  val cmd_nop :: cmd_act :: cmd_pre :: cmd_casw ::  cmd_casr :: cmd_ref :: Nil = Enum(6)
  val bank_idle :: bank_active :: Nil = Enum(numBankStates)
  val rank_active :: rank_refresh :: Nil = Enum(numRankStates)
}


case class JSONField(value: BigInt, units: String)

class DRAMProgrammableTimings extends Bundle with HasDRAMMASConstants with HasProgrammableRegisters
    with HasConsoleUtils {
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


  def tCAS2tCWL(tCAS: BigInt) = {
    require(tCAS > 4)
    if      (tCAS > 12 ) tCAS - 4
    else if (tCAS > 9)   tCAS - 3
    else if (tCAS > 7)   tCAS - 2
    else if (tCAS > 5)   tCAS - 1
    else                 tCAS
  }

  // Defaults are set to sg093, x8, 2048Mb density (1GHz clock)
  val registers = Seq(
    tAL   -> RuntimeSetting(0,"Additive Latency"),
    tCAS  -> JSONSetting(14,  "CAS Latency",                    { _("CL_TIME") }),
    tCMD  -> JSONSetting(1,   "Command Transport Time",         { lut => 1 }),
    tCWD  -> JSONSetting(10,  "Write CAS Latency",              { lut =>  tCAS2tCWL(lut("CL_TIME")) }),
    tCCD  -> JSONSetting(4,   "Column-to-Column Delay",         { _("TCCD") }),
    tFAW  -> JSONSetting(25,  "Four row-Activation Window",     { _("TFAW") }),
    tRAS  -> JSONSetting(33,  "Row Access Strobe Delay",        { _("TRAS_MIN") }),
    tREFI -> JSONSetting(7800,"REFresh Interval",               { _("TRFC_MAX")/9 }),
    tRC   -> JSONSetting(47,  "Row Cycle time",                 { _("TRC") }),
    tRCD  -> JSONSetting(14,  "Row-to-Column Delay",            { _("TRCD") }),
    tRFC  -> JSONSetting(160, "ReFresh Cycle time",             { _("TRFC_MIN") }),
    tRRD  -> JSONSetting(8,   "Row-to-Row Delay",               { _("TRRD") }),
    tRP   -> JSONSetting(14,  "Row-Precharge delay",            { _("TRP") }),
    tRTP  -> JSONSetting(8,   "Read-To-Precharge delay",        { lut => lut("TRTP").max(lut("TRTP_TCK")) }),
    tRTRS -> JSONSetting(2,   "Rank-to-Rank Switching Time",    { lut => 2 }), // FIXME
    tWR   -> JSONSetting(15,  "Write-Recovery time",            { _("TWR") }),
    tWTR  -> JSONSetting(8,   "Write-To-Read Turnaround Time",  { _("TWTR") })
  )

  def setDependentRegisters(lut: Map[String, JSONField], freqMHz: BigInt): Unit = {
    val periodPs = 1000000.0/freqMHz.toFloat
    // Generate a lookup table of timings in units of tCK (as all programmable
    // timings in the model are in units of the controller clock frequency
    val lutTCK = lut.flatMap({
      case (name , JSONField(value, "ps")) =>
        Some(name -> BigInt(((value.toFloat + periodPs - 1)/periodPs).toInt))
      case (name , JSONField(value, "tCK")) => Some(name -> value)
      case _ => None
    })

    registers foreach {
      case (elem, reg: JSONSetting) => reg.setWithLUT(lutTCK)
      case _ => None
    }
  }
}

case class DRAMBackendKey(writeDepth: Int, readDepth: Int, latencyBits: Int)

abstract class DRAMBaseConfig extends BaseConfig with HasDRAMMASConstants {
  def dramKey: DramOrganizationParams
  def backendKey: DRAMBackendKey
}

abstract class BaseDRAMMMRegIO(cfg: DRAMBaseConfig) extends MMRegIO(cfg) with HasConsoleUtils {

  // The default assignment corresponde to a standard open-page policy
  // with 8K pages. All available ranks are enabled.
  val bankAddr = Input(new ProgrammableSubAddr(
    maskBits = cfg.dramKey.bankBits,
    longName = "Bank Address",
    defaultOffset = 13, // Assume 8KB page size
    defaultMask = 7 // DDR3 Has 8 banks
  ))

  val rankAddr = Input(new ProgrammableSubAddr(
    maskBits = cfg.dramKey.rankBits,
    longName = "Rank Address",
    defaultOffset = bankAddr.defaultOffset + log2Ceil(bankAddr.defaultMask + 1),
    defaultMask = (1 << cfg.dramKey.rankBits) - 1
  ))

  val defaultRowOffset = rankAddr.defaultOffset + log2Ceil(rankAddr.defaultMask + 1)
  val rowAddr = Input(new ProgrammableSubAddr(
    maskBits = cfg.dramKey.rowBits,
    longName = "Row Address",
    defaultOffset = defaultRowOffset,
    defaultMask = (cfg.dramKey.dramSize >> defaultRowOffset.toInt) - 1
  ))

  // Page policy 1 = open, 0 = closed
  val openPagePolicy = Input(Bool())
  // Additional latency added to read data beats after it's received from the devices
  val backendLatency = Input(UInt(cfg.backendKey.latencyBits.W))

  // Counts the number of misses in the open row buffer
  //val rowMisses = Output(UInt(32.W))
  val dramTimings = Input(new DRAMProgrammableTimings())
  val rankPower = Output(Vec(cfg.dramKey.maxRanks, new RankPowerIO))


  // END CHISEL TYPES
  val dramBaseRegisters = Seq(
    (openPagePolicy -> RuntimeSetting(1, "Open-Page Policy")),
    (backendLatency -> RuntimeSetting(2,
                                      "Backend Latency",
                                      min = 1,
                                      max = Some(1 << (cfg.backendKey.latencyBits - 1))))
  )

  // A list of DDR3 speed grades provided by micron.
  // _1 = is used as a key to look up a device,  _2 = long name
  val speedGrades = Seq(
    ("sg093" -> "DDR3-2133 (14-14-14)  Minimum Clock Period: 938 ps"),
    ("sg107" -> "DDR3-1866 (13-13-13)  Minimum Clock Period: 1071 ps"),
    ("sg125" -> "DDR3-1600 (11-11-11)  Minimum Clock Period: 1250 ps"),
    ("sg15E" -> "DDR3-1333H (9-9-9)  Minimum Clock Period: 1500 ps"),
    ("sg15" -> "DDR3-1333J (10-10-10)  Minimum Clock Period: 1500 ps"),
    ("sg187U" -> "DDR3-1066F (7-7-7)  Minimum Clock Period: 1875 ps"),
    ("sg187" -> "DDR3-1066G (8-8-8)  Minimum Clock Period: 1875 ps"),
    ("sg25E" -> "DDR3-800E (5-5-5)  Minimum Clock Period: 2500 ps"),
    ("sg25" -> "DDR3-800 (6-6-6)  Minimum Clock Period: 2500 ps")
  )

  // Prompt the user for an address assignment scheme. TODO: Channel bits.
  def getAddressScheme(
      numRanks: BigInt,
      numBanks: BigInt,
      numRows: BigInt,
      numBytesPerLine: BigInt,
      pageSize: BigInt): Unit = {

    case class SubAddr(
        shortName: String,
        longName: String,
        field: Option[ProgrammableSubAddr],
        count: BigInt) {
      require(isPow2(count))
      val bits = log2Ceil(count)
      def set(offset: Int): Unit = { field.foreach( _.forceSettings(offset, count - 1) ) }
      def legendEntry: String = s"  ${shortName} -> ${longName}"
    }

    val ranks       = SubAddr("L", "Rank Address Bits", Some(rankAddr), numRanks)
    val banks       = SubAddr("B", "Bank Address Bits", Some(bankAddr), numBanks)
    val rows        = SubAddr("R", "Row Address Bits", Some(rowAddr),  numRows)
    val linesPerRow = SubAddr("N", "log2(Lines Per Row)", None, pageSize/numBytesPerLine)
    val bytesPerLine= SubAddr("Z", "log2(Bytes Per Line)", None, numBytesPerLine)

    // Address schemes
    // _1 = long name, _2 = A seq of subfields from address MSBs to LSBs
    val addressSchemes = Seq(
      "Baseline Open   " -> Seq(rows, ranks, banks, linesPerRow, bytesPerLine),
      "Baseline Closed " -> Seq(rows, linesPerRow, ranks, banks, bytesPerLine)
    )

    val legendHeader = s"${UNDERLINED}Legend${RESET}\n"
    val legendBody   = (addressSchemes.head._2 map {_.legendEntry}).mkString("\n")

    val schemeStrings = addressSchemes map { case (name, addrOrder) =>
      val shortNameOrder = (addrOrder map { _.shortName }).mkString(" | ")
      s"${name} -> ( ${shortNameOrder} ) "
    }

    val scheme = addressSchemes(requestSeqSelection(
      "Select an address assignment scheme:",
      schemeStrings,
      legendHeader + legendBody + "\nAddress scheme number"))._2

    def setSubAddresses(ranges: Seq[SubAddr], offset: Int = 0): Unit = ranges match {
      case current :: moreSigFields =>
        current.set(offset)
        setSubAddresses(moreSigFields, offset + current.bits)
      case Nil => None
    }
    setSubAddresses(scheme.reverse)
  }

  // Prompt the user for a speedgrade selection. TODO: illegalize SGs based on frequency
  def getSpeedGrade(): String = {
    speedGrades(requestSeqSelection("Select a speed grade:", speedGrades.unzip._2))._1
  }

  // Get the parameters (timings, bitwidths etc..) for a paticular device from jsons in resources/
  def lookupPart(density: BigInt, dqWidth: BigInt, speedGrade: String): Map[String, JSONField] = {
    val dqKey = "x" + dqWidth.toString
    val stream = getClass.getResourceAsStream(s"/midas/models/dram/${density}Mb_ddr3.json")
    val lines = Source.fromInputStream(stream).getLines
    implicit val formats = org.json4s.DefaultFormats
    val json = parse(lines.mkString).extract[Map[String, Map[String, Map[String, JSONField]]]]
    json(speedGrade)(dqKey)
  }

  def setBaseDRAMSettings(): Unit = {

    // Prompt the user for overall memory organization of this channel
    Console.println(s"${UNDERLINED}Memory system organization${RESET}")
    val memorySize = requestInput("Memory system size in GiB", 2)
    val numRanks =   requestInput("Number of ranks", 1)
    val busWidth =   requestInput("DRAM data bus width in bits", 64)
    val dqWidth =    requestInput("Device DQ width", 8)

    val devicesPerRank = busWidth / dqWidth
    val deviceDensityMib = ((memorySize << 30) * 8 / numRanks / devicesPerRank) >> 20
    Console.println(s"${GREEN}Selected Device density (Mib) -> ${deviceDensityMib}${RESET}")

    // Select the appropriate device, and look up it's parameters in resource jsons
    Console.println(s"\n${UNDERLINED}Device Selection${RESET}")
    val freqMHz       = requestInput("Clock Frequency in MHz", 1000)
    val speedGradeKey = getSpeedGrade()

    val lut = lookupPart(deviceDensityMib, dqWidth, speedGradeKey)
    val dramTimingSettings = dramTimings.setDependentRegisters(lut, freqMHz)

    // Determine the address assignment scheme
    Console.println(s"\n${UNDERLINED}Address assignment${RESET}")
    val lineSize = requestInput("Line size in Bytes", 64)

    val numBanks = 8  // DDR3 Mandated
    val pageSize = ((BigInt(1) << lut("COL_BITS").value.toInt) * devicesPerRank * dqWidth ) / 8
    val numRows = BigInt(1) << lut("ROW_BITS").value.toInt
    getAddressScheme(numRanks, numBanks,  numRows, lineSize, pageSize)
  }
}

case class DramOrganizationParams(maxBanks: Int, maxRanks: Int, dramSize: BigInt, lineBits: Int = 8) {
  require(isPow2(maxBanks))
  require(isPow2(maxRanks))
  require(isPow2(dramSize))
  require(isPow2(lineBits))
  def bankBits = log2Up(maxBanks)
  def rankBits = log2Up(maxRanks)
  def rowBits  = log2Ceil(dramSize) - lineBits
  def maxRows  = 1 << rowBits
}

trait CommandLegalBools {
  val canCASW = Output(Bool())
  val canCASR = Output(Bool())
  val canPRE  = Output(Bool())
  val canACT  = Output(Bool())
}

trait HasLegalityUpdateIO {
  val key: DramOrganizationParams
  import DRAMMasEnums._
  val timings = Input(new DRAMProgrammableTimings)
  val selectedCmd = Input(cmd_nop.cloneType)
  val autoPRE = Input(Bool())
  val cmdRow = Input(UInt(key.rowBits.W))
  //val burstLength = Input(UInt(4.W)) // TODO: Fixme
}

// Add some scheduler specific metadata to a reference
// TODO factor out different MAS metadata into a mixin
class MASEntry(key: DRAMBaseConfig)(implicit p: Parameters) extends Bundle {
  val xaction = new TransactionMetaData
  val rowAddr = UInt(key.dramKey.rowBits.W)
  val bankAddrOH = UInt(key.dramKey.maxBanks.W)
  val bankAddr = UInt(key.dramKey.bankBits.W)
  val rankAddrOH = UInt(key.dramKey.maxRanks.W)
  val rankAddr = UInt(key.dramKey.rankBits.W)

  def decode(from: XactionSchedulerEntry, mmReg: BaseDRAMMMRegIO): Unit = {
    xaction := from.xaction
    bankAddr := mmReg.bankAddr.getSubAddr(from.addr)
    bankAddrOH := UIntToOH(bankAddr)
    rowAddr := mmReg.rowAddr.getSubAddr(from.addr)
    rankAddr := mmReg.rankAddr.getSubAddr(from.addr)
    rankAddrOH := UIntToOH(rankAddr)
  }

  def addrMatch(rank: UInt, bank: UInt, row: Option[UInt] = None): Bool = {
    val rowHit =  row.foldLeft(true.B)({ case (p, addr) => p && addr === rowAddr })
    rank === rankAddr && bank === bankAddr && rowHit
  }
}

class FirstReadyFCFSEntry(key: DRAMBaseConfig)(implicit p: Parameters) extends MASEntry(key)(p) {
  val isReady = Bool() //Set when entry hits in open row buffer
  val mayPRE = Bool() // Set when no other entires hit open row buffer

  // We only ask for a precharge, if we have permission (no other references hit)
  // and the entry isn't personally ready
  def wantPRE(): Bool = !isReady && mayPRE // Don't need the dummy args
  def wantACT(): Bool = !isReady
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

class BankStateTrackerO(key: DramOrganizationParams) extends GenericParameterizedBundle(key)
    with CommandLegalBools {

  import DRAMMasEnums._
  val openRow = Output(UInt(key.rowBits.W))
  val state = Output(Bool())

  def isRowHit(ref: MASEntry): Bool = ref.rowAddr === openRow && state === bank_active
}

class BankStateTrackerIO(val key: DramOrganizationParams) extends GenericParameterizedBundle(key)
  with HasLegalityUpdateIO {
  val out = new BankStateTrackerO(key)
  val cmdUsesThisBank = Input(Bool())
}

class BankStateTracker(key: DramOrganizationParams) extends Module with HasDRAMMASConstants {
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
    mod.io.set.bits := DontCare
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

class RankStateTrackerO(key: DramOrganizationParams) extends GenericParameterizedBundle(key)
    with CommandLegalBools {
  import DRAMMasEnums._
  val canREF = Output(Bool())
  val wantREF = Output(Bool())
  val state = Output(rank_active.cloneType)
  val banks = Vec(key.maxBanks, Output(new BankStateTrackerO(key)))
}

class RankStateTrackerIO(val key: DramOrganizationParams) extends GenericParameterizedBundle(key)
    with HasLegalityUpdateIO with HasDRAMMASConstants {
  val rank = new RankStateTrackerO(key)
  val tCycle = Input(UInt(maxDRAMTimingBits.W))
  val cmdUsesThisRank = Input(Bool())
  val cmdBankOH = Input(UInt(key.maxBanks.W))
}

class RankStateTracker(key: DramOrganizationParams) extends Module with HasDRAMMASConstants {
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
    mod.io.set.bits := DontCare
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

  // Disable refresion by setting tREFI = 0
  when (tREFI === io.timings.tREFI && io.timings.tREFI =/= 0.U) {
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

  bankTrackers.zip(io.cmdBankOH.asBools) foreach { case (bank, cmdUsesThisBank)  =>
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
  when (io.cmd =/= cmd_nop) {
    lastCommand := cycleCounter
    when (lastCommand + 1.U =/= cycleCounter) { printf("nop(%d);\n", cycleCounter - lastCommand - 1.U) }
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

class RankRefreshUnitIO(key: DramOrganizationParams) extends GenericParameterizedBundle(key) {
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

class RefreshUnit(key: DramOrganizationParams) extends Module {
  val io = IO(new RankRefreshUnitIO(key))

  val ranksWantingRefresh = VecInit(io.rankStati map { _.wantREF }).asUInt
  val refreshableRanks = VecInit(io.rankStati map { _.canREF }).asUInt & io.ranksInUse

  io.refRankAddr := PriorityEncoder(ranksWantingRefresh & refreshableRanks)
  io.suggestREF := (ranksWantingRefresh & refreshableRanks).orR

  // preRef => a precharge needed before refresh may occur
  val preRefBanks = io.rankStati map { rank => PriorityEncoder(rank.banks map { _.canPRE })}

  val prechargeableRanks = VecInit(io.rankStati map { rank => rank.canPRE &&
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
    val w = Wire(new RankPowerIO)
    w.allPreCycles := 0.U
    w.numCASR := 0.U
    w.numCASW := 0.U
    w.numACT := 0.U
    w
  }
}

class RankPowerMonitor(key: DramOrganizationParams) extends Module with HasDRAMMASConstants {
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
  when (io.rankState.state =/= rank_refresh && ((io.rankState.banks) forall { _.canACT })) {
    stats.allPreCycles := stats.allPreCycles + 1.U
  }

  io.stats := stats
}

class DRAMBackendIO(val latencyBits: Int)(implicit val p: Parameters) extends Bundle {
  val newRead = Flipped(Decoupled(new ReadResponseMetaData))
  val newWrite = Flipped(Decoupled(new WriteResponseMetaData))
  val completedRead = Decoupled(new ReadResponseMetaData)
  val completedWrite = Decoupled(new WriteResponseMetaData)
  val readLatency = Input(UInt(latencyBits.W))
  val writeLatency = Input(UInt(latencyBits.W))
  val tCycle = Input(UInt(latencyBits.W))
}

class DRAMBackend(key: DRAMBackendKey)(implicit p: Parameters) extends Module {
  val io = IO(new DRAMBackendIO(key.latencyBits))
  val rQueue = Module(new DynamicLatencyPipe(new ReadResponseMetaData, key.readDepth, key.latencyBits))
  val wQueue = Module(new DynamicLatencyPipe(new WriteResponseMetaData, key.writeDepth, key.latencyBits))

  io.completedRead <> rQueue.io.deq
  io.completedWrite <> wQueue.io.deq
  rQueue.io.enq <> io.newRead
  rQueue.io.latency := io.readLatency
  wQueue.io.enq <> io.newWrite
  wQueue.io.latency := io.writeLatency
  Seq(rQueue, wQueue) foreach { _.io.tCycle := io.tCycle }
}
