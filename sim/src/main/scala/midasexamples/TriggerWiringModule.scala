//See LICENSE for license details.

package firesim.midasexamples

import midas.widgets.{RationalClockBridge, PeekPokeBridge}
import midas.targetutils.{TriggerSource, TriggerSink}
import freechips.rocketchip.util.{DensePrefixSum, ResetCatchAndSync}
import chisel3._
import chisel3.util._
import chisel3.experimental.chiselName

import scala.collection.mutable

class TriggerSinkModule extends MultiIOModule {
  val reference = IO(Input(Bool()))
  val generated = WireDefault(true.B)
  TriggerSink(generated)
  assert(reference === generated)
}

trait SourceCredit { self: MultiIOModule =>
  val referenceCredit = IO(Output(Bool()))
  private val lfsr = LFSR16()
  val credit = lfsr(0)
  TriggerSource.credit(credit)
  referenceCredit := ~reset.toBool && credit
}

trait SourceDebit { self: MultiIOModule =>
  val referenceDebit = IO(Output(Bool()))
  private val lfsr = LFSR16()
  val debit = ShiftRegister(lfsr(0), 5)
  TriggerSource.debit(debit)
  referenceDebit := ~reset.toBool && debit
}

class TriggerSourceModule extends MultiIOModule with SourceCredit with SourceDebit
class TriggerCreditModule extends MultiIOModule with SourceCredit
class TriggerDebitModule extends MultiIOModule with SourceDebit


class ReferenceSourceCounters(numCredits: Int, numDebits: Int) extends MultiIOModule {
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

  @chiselName
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
class TriggerWiringModule extends RawModule {
  val clockBridge = Module(new RationalClockBridge(1000, (1,2)))
  val refClock :: div2Clock :: _ = clockBridge.io.clocks.toList
  val refSourceCounts = new mutable.ArrayBuffer[ReferenceSourceCounters]()
  val refSinks = new mutable.ArrayBuffer[Bool]()
  val reset = WireInit(false.B)
  val resetHalfRate = ResetCatchAndSync(div2Clock, reset.toBool)
  withClockAndReset(refClock, reset) {
    val peekPokeBridge = PeekPokeBridge(refClock, reset)
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

  @chiselName
  class ReferenceImpl {
    val totalCredit = Reg(UInt(32.W))
    val totalDebit  = Reg(UInt(32.W))
    val creditNext  = totalCredit + DensePrefixSum(refSourceCounts.map(_.syncAndDiffCredits))(_ + _).last
    val debitNext = totalDebit + DensePrefixSum(refSourceCounts.map(_.syncAndDiffDebits))(_ + _).last
    totalCredit := creditNext
    totalDebit  := debitNext
    val triggerEnable = creditNext =/= debitNext
    refSinks foreach { _ := triggerEnable }
  }

  // Reference Trigger Enable
  withClock(refClock) { new ReferenceImpl }
}
