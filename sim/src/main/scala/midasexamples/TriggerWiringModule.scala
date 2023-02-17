//See LICENSE for license details.

package firesim.midasexamples

import midas.widgets.{RationalClockBridge, PeekPokeBridge, RationalClock}
import freechips.rocketchip.util.{DensePrefixSum, ResetCatchAndSync}
import freechips.rocketchip.config.Parameters
import chisel3._
import chisel3.util._

import scala.collection.mutable

class TriggerSinkModule extends Module {
  val reference = IO(Input(Bool()))
  // DOC include start: TriggerSink Usage
  // Note: this can be any reference you wish to have driven by the trigger.
  val sinkBool = WireDefault(true.B)

  import midas.targetutils.TriggerSink
  // Drives true.B if no TriggerSource credits exist in the design.
  // Note: noSourceDefault defaults to true.B if unset, and can be omitted for brevity
  TriggerSink(sinkBool, noSourceDefault = true.B)
  // DOC include end: TriggerSink Usage
  assert(reference === sinkBool)
}

class TriggerSourceModule extends Module {
  val referenceCredit = IO(Output(Bool()))
  val referenceDebit = IO(Output(Bool()))
  private val lfsr = random.LFSR(16)

  // DOC include start: TriggerSource Usage
  // Some arbitarily logic to drive the credit source and sink. Replace with your own!
  val start = lfsr(1)
  val stop = ShiftRegister(lfsr(0), 5)

  // Now annotate the signals.
  import midas.targetutils.TriggerSource
  TriggerSource.credit(start)
  TriggerSource.debit(stop)
  // Note one could alternatively write: TriggerSource(start, stop)
  // DOC include end: TriggerSource Usage

  referenceCredit := ~reset.asBool && start
  referenceDebit := ~reset.asBool && stop
}

class LevelSensitiveTriggerSourceModule extends Module {
  val referenceCredit = IO(Output(Bool()))
  val referenceDebit = IO(Output(Bool()))
  private val enable = random.LFSR(16)(0)

  // DOC include start: TriggerSource Level-Sensitive Usage
  import midas.targetutils.TriggerSource
  TriggerSource.levelSensitiveEnable(enable)
  // DOC include end: TriggerSource Level-Sensitive Usage

  val enLast = RegNext(enable)
  referenceCredit := !enLast && enable
  referenceDebit  := enLast && !enable
}

class ReferenceSourceCounters(numCredits: Int, numDebits: Int) extends Module {
  def counterType = UInt(16.W)
  val inputCredits = IO(Input(Vec(numCredits, Bool())))
  val inputDebits  = IO(Input(Vec(numCredits, Bool())))
  val totalCredit  = IO(Output(counterType))
  val totalDebit   = IO(Output(counterType))

  def doAccounting(values: Seq[Bool]): UInt = {
    val total = Reg(counterType)
    val update = total + PopCount(values)
    total := update
    update
  }
  totalCredit := doAccounting(inputCredits)
  totalDebit := doAccounting(inputDebits)

  def synchAndDiff(count: UInt): UInt = {
    val sync = RegNext(count)
    val syncLast = RegNext(sync)
    sync - syncLast
  }
  def syncAndDiffCredits(): UInt = synchAndDiff(totalCredit)
  def syncAndDiffDebits(): UInt = synchAndDiff(totalDebit)
}

object ReferenceSourceCounters {
  def apply(credits: Seq[Bool], debits: Seq[Bool]): ReferenceSourceCounters = {
    val m = Module(new ReferenceSourceCounters(credits.size, debits.size))
    m.inputCredits := VecInit(credits)
    m.inputDebits := VecInit(debits)
    m
  }
}

// This test target implements in Chisel what the Trigger Transformation should
// implement in FIRRTL. The test fails if the firrtl-generated trigger-enables,
// as seen by all nodes with a trigger sink, fail to match their references.
class TriggerWiringModule(implicit p: Parameters) extends RawModule {
  val clockBridge = RationalClockBridge(RationalClock("HalfRate", 1, 2))
  val refClock :: div2Clock :: _ = clockBridge.io.clocks.toList
  val refSourceCounts = new mutable.ArrayBuffer[ReferenceSourceCounters]()
  val refSinks = new mutable.ArrayBuffer[Bool]()
  val reset = WireInit(false.B)
  val resetHalfRate = ResetCatchAndSync(div2Clock, reset.asBool)
  withClockAndReset(refClock, reset) {
    val peekPokeBridge = PeekPokeBridge(refClock, reset)
    val src  = Module(new TriggerSourceModule)
    val sink = Module(new TriggerSinkModule)

    val levelSensitiveSrc = Module(new LevelSensitiveTriggerSourceModule)

    // Reference Hardware
    refSourceCounts += ReferenceSourceCounters(
      Seq(src.referenceCredit, levelSensitiveSrc.referenceCredit),
      Seq(src.referenceDebit, levelSensitiveSrc.referenceDebit))
    refSinks += {
      val syncReg = Reg(Bool())
      sink.reference := syncReg
      syncReg
    }
  }

  withClockAndReset(div2Clock, resetHalfRate) {
    val src  = Module(new TriggerSourceModule)
    val sink = Module(new TriggerSinkModule)
    // Reference Hardware
    refSourceCounts += ReferenceSourceCounters(Seq(src.referenceCredit), Seq(src.referenceDebit))
    refSinks += {
      val syncReg = Reg(Bool())
      sink.reference := syncReg
      syncReg
    }
  }

  class ReferenceImpl {
    val refTotalCredit = Reg(UInt(32.W))
    val refTotalDebit  = Reg(UInt(32.W))
    val refCreditNext  = refTotalCredit + DensePrefixSum(refSourceCounts.toSeq.map(_.syncAndDiffCredits))(_ + _).last
    val refDebitNext   = refTotalDebit + DensePrefixSum(refSourceCounts.toSeq.map(_.syncAndDiffDebits))(_ + _).last
    refTotalCredit := refCreditNext
    refTotalDebit  := refDebitNext
    val refTriggerEnable = refCreditNext =/= refDebitNext
    refSinks foreach { _ := refTriggerEnable }
  }

  // Reference Trigger Enable
  withClock(refClock) { new ReferenceImpl }
}
