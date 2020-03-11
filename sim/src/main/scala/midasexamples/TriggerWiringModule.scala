//See LICENSE for license details.

package firesim.midasexamples

import midas.widgets.{RationalClockBridge, PeekPokeBridge}
import midas.targetutils.{TriggerSource, TriggerSink}
import freechips.rocketchip.util.DensePrefixSum
import chisel3._
import chisel3.util._

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
  referenceCredit := lfsr(0)
  val credit = Wire(Bool())
  credit := referenceCredit
  TriggerSource.credit(credit)
}

trait SourceDebit { self: MultiIOModule =>
  val referenceDebit = IO(Output(Bool()))
  private val lfsr = LFSR16()
  referenceDebit := lfsr(0) ^ lfsr(1)
  val debit = Wire(Bool())
  debit := referenceDebit
  TriggerSource.credit(debit)
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
  val refClock :: div2Clock = clockBridge.io.clocks.toList
  val refSourceCounts = new mutable.ArrayBuffer[ReferenceSourceCounters]()
  val refSinks = new mutable.ArrayBuffer[Bool]()
  val reset = WireInit(false.B)
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

  // Reference Trigger Enable
  withClockAndReset(refClock, reset) {
    val totalCredit = Reg(UInt(32.W))
    val totalDebit  = Reg(UInt(32.W))
    totalCredit := totalCredit + DensePrefixSum(refSourceCounts.map(_.syncAndDiffCredits))(_ + _).last
    totalDebit  := totalDebit  + DensePrefixSum(refSourceCounts.map(_.syncAndDiffDebits))(_ + _).last
    val triggerEnable = totalCredit =/= totalDebit
    refSinks foreach { _ := triggerEnable }
  }
}
