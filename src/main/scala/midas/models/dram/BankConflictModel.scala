package midas
package models

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.util.GenericParameterizedBundle
import junctions._
import midas.widgets._

class BankConflictMMRegIO(cfg: BankConflictConfig) extends MMRegIO(cfg: BaseConfig){
  val latency = Input(UInt(32.W))
  val conflictPenalty = Input(UInt(32.W))
  //  The mask bits setting determines how many banks are used
  val bankAddr = Input(new ProgrammableSubAddr(log2Ceil(cfg.maxBanks)))

  val bankConflicts = Output(Vec(cfg.maxBanks, UInt(32.W)))
}

case class BankConflictConfig(
    maxBanks: Int,
    maxLatencyBits: Int = 12, // 4K cycles
    baseParams: BaseParams)
  extends BaseConfig(baseParams) {

  def elaborate()(implicit p: Parameters): BankConflictModel = Module(new BankConflictModel(this))
}

class BankConflictIO(cfg: BankConflictConfig)(implicit p: Parameters)
    extends TimingModelIO(cfg)(p) {
  val mmReg = new BankConflictMMRegIO(cfg)
}

case class BankConflictReferenceKey(cycleBits: Int, idBits: Int, lenBits: Int, bankAddrBits: Int)

class BankQueueEntry(key: BankConflictReferenceKey) extends GenericParameterizedBundle(key) {
  val xaction = new TransactionMetaData(MetaDataWidths(key.idBits, key.lenBits))
  val bankAddr = UInt(key.bankAddrBits.W)
}

// Appends a target cycle at which this reference should be complete
class BankConflictReference(key: BankConflictReferenceKey) extends GenericParameterizedBundle(key) {
  val reference = new BankQueueEntry(key)
  val cycle = UInt(key.cycleBits.W) // Indicates latency until doneness
  val done = Bool() // Set high when the cycle count expires
}

object BankConflictConstants {
  val nBankStates = 3
  val bankIdle :: bankBusy :: bankPrecharge :: Nil = Enum(UInt(), nBankStates)
}

import BankConflictConstants._

class BankConflictModel(cfg: BankConflictConfig)(implicit p: Parameters) extends TimingModel(cfg)(p) {
  // This is the absolute number of banks the model can account for
  lazy val io = IO(new BankConflictIO(cfg))

  val latency = io.mmReg.latency
  val conflictPenalty = io.mmReg.conflictPenalty

  val refKey = BankConflictReferenceKey(cfg.maxLatencyBits, nastiXIdBits, nastiXLenBits, log2Up(cfg.maxBanks))

  val transactionQueue = Module(new DualQueue(
      gen = new BankQueueEntry(refKey),
      entries = cfg.maxWrites + cfg.maxReads))

  transactionQueue.io.enqA.valid := newWReq
  transactionQueue.io.enqA.bits.xaction.id := awQueue.io.deq.bits.id
  transactionQueue.io.enqA.bits.xaction.len := awQueue.io.deq.bits.len
  transactionQueue.io.enqA.bits.xaction.isWrite := true.B
  transactionQueue.io.enqA.bits.bankAddr := io.mmReg.bankAddr.getSubAddr(awQueue.io.deq.bits.addr)

  transactionQueue.io.enqB.valid := tNasti.ar.fire
  transactionQueue.io.enqB.bits.xaction.id := tNasti.ar.bits.id
  transactionQueue.io.enqB.bits.xaction.len := tNasti.ar.bits.len
  transactionQueue.io.enqB.bits.xaction.isWrite := false.B
  transactionQueue.io.enqB.bits.bankAddr := io.mmReg.bankAddr.getSubAddr(tNasti.ar.bits.addr)

  val bankBusyCycles = Seq.fill(cfg.maxBanks)(RegInit(UInt(0, cfg.maxLatencyBits)))
  val bankConflictCounts = RegInit(Vec.fill(cfg.maxBanks)(0.U(32.W)))

  val refList = Seq.fill(cfg.maxReads + cfg.maxWrites)(
     RegInit({val w = Wire(Valid(new BankConflictReference(refKey))); w.valid := false.B; w}))

  val newReference = Wire(Decoupled(new BankConflictReference(refKey)))
  newReference.valid := transactionQueue.io.deq.valid
  newReference.bits.reference := transactionQueue.io.deq.bits
  val marginalCycles = latency + Vec(bankBusyCycles)(transactionQueue.io.deq.bits.bankAddr)
  newReference.bits.cycle := tCycle(cfg.maxLatencyBits-1, 0) + marginalCycles
  newReference.bits.done := marginalCycles === 0.U
  transactionQueue.io.deq.ready := newReference.ready

  val refUpdates = CollapsingBuffer(refList, newReference)

  bankBusyCycles.zip(bankConflictCounts).zipWithIndex.foreach({ case ((busyCycles, conflictCount), idx) =>
    when(busyCycles > 0.U){
      busyCycles := busyCycles - 1.U
    }

    when(newReference.fire() && newReference.bits.reference.bankAddr === idx.U){
      busyCycles := marginalCycles + conflictPenalty
      conflictCount := Mux(busyCycles > 0.U, conflictCount + 1.U, conflictCount)
    }
  })

  // Mark the reference as complete
  refList.zip(refUpdates).foreach({ case (ref, update) =>
    when(tCycle(cfg.maxLatencyBits-1, 0) === ref.bits.cycle) { update.bits.done := true.B }
  })

  val selector =  Module(new Arbiter(refList.head.bits.cloneType, refList.size))
  //Shouldn't be required
  selector.io.out.ready := true.B
  selector.io.in <> Vec(refList.map({ entry =>
      val candidate = V2D(entry)
      candidate.valid := entry.valid && entry.bits.done
      candidate
    }))

  // Take the readies from the arbiter, and kill the selected entry
  refUpdates.zip(selector.io.in).foreach({ case (ref, sel) =>
    when(sel.fire()) { ref.valid := false.B } })

  io.mmReg.bankConflicts := bankConflictCounts

  lazy val backend = Module(new UnifiedAXI4Backend(cfg))

  val completedRef = selector.io.out.bits.reference
  backend.io.newXaction.bits := completedRef.xaction
  backend.io.newXaction.valid := selector.io.out.valid
}
