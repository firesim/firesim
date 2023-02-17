package midas
package models

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import midas.widgets._

import Console.{UNDERLINED, RESET}

case class BankConflictConfig(
    maxBanks: Int,
    maxLatencyBits: Int = 12, // 4K cycles
    params: BaseParams) extends BaseConfig {

  def elaborate()(implicit p: Parameters): BankConflictModel = Module(new BankConflictModel(this))
}

class BankConflictMMRegIO(cfg: BankConflictConfig)(implicit p: Parameters)
    extends SplitTransactionMMRegIO(cfg){
  val latency = Input(UInt(cfg.maxLatencyBits.W))
  val conflictPenalty = Input(UInt(32.W))
  //  The mask bits setting determines how many banks are used
  val bankAddr = Input(new ProgrammableSubAddr(
    maskBits = log2Ceil(cfg.maxBanks),
    longName = "Bank Address",
    defaultOffset = 13,
    defaultMask = (1 << cfg.maxBanks) - 1
  ))

  val bankConflicts = Output(Vec(cfg.maxBanks, UInt(32.W)))

  val registers = maxReqRegisters ++ Seq(
    (latency         -> RuntimeSetting(30,
                                       "Latency",
                                       min = 1,
                                       max = Some((1 << (cfg.maxLatencyBits-1)) - 1))),
    (conflictPenalty -> RuntimeSetting(30,
                                      "Bank-Conflict Penalty",
                                      max = Some((1 << (cfg.maxLatencyBits-1)) - 1)))
  )

  def requestSettings(): Unit = {
    Console.println(s"${UNDERLINED}Generating runtime configuration for Bank-Conflict Model${RESET}")
  }
}

class BankConflictIO(cfg: BankConflictConfig)(implicit p: Parameters)
    extends SplitTransactionModelIO()(p) {
  val mmReg = new BankConflictMMRegIO(cfg)
}

class BankQueueEntry(cfg: BankConflictConfig)(implicit p: Parameters) extends Bundle {
  val xaction = new TransactionMetaData
  val bankAddr = UInt(log2Ceil(cfg.maxBanks).W)
}

// Appends a target cycle at which this reference should be complete
class BankConflictReference(cfg: BankConflictConfig)(implicit p: Parameters) extends Bundle {
  val reference = new BankQueueEntry(cfg)
  val cycle = UInt(cfg.maxLatencyBits.W) // Indicates latency until doneness
  val done = Bool() // Set high when the cycle count expires
}

object BankConflictConstants {
  val nBankStates = 3
  val bankIdle :: bankBusy :: bankPrecharge :: Nil = Enum(nBankStates)
}


class BankConflictModel(cfg: BankConflictConfig)(implicit p: Parameters) extends SplitTransactionModel(cfg)(p) {

  val longName = "Bank Conflict"
  def printTimingModelGenerationConfig: Unit = {}
  /**************************** CHISEL BEGINS *********************************/
  // This is the absolute number of banks the model can account for
  lazy val io = IO(new BankConflictIO(cfg))

  val latency = io.mmReg.latency
  val conflictPenalty = io.mmReg.conflictPenalty

  val transactionQueue = Module(new Queue(new BankQueueEntry(cfg), cfg.maxWrites + cfg.maxReads))
  val transactionQueueArb = Module(new RRArbiter(new BankQueueEntry(cfg), 2))

  transactionQueueArb.io.in(0).valid := newWReq
  transactionQueueArb.io.in(0).bits.xaction := TransactionMetaData(awQueue.io.deq.bits)
  transactionQueueArb.io.in(0).bits.bankAddr := io.mmReg.bankAddr.getSubAddr(awQueue.io.deq.bits.addr)

  transactionQueueArb.io.in(1).valid := tNasti.ar.fire
  transactionQueueArb.io.in(1).bits.xaction := TransactionMetaData(tNasti.ar.bits)
  transactionQueueArb.io.in(1).bits.bankAddr := io.mmReg.bankAddr.getSubAddr(tNasti.ar.bits.addr)

  transactionQueue.io.enq <> transactionQueueArb.io.out

  val bankBusyCycles = Seq.fill(cfg.maxBanks)(RegInit(0.U(cfg.maxLatencyBits.W)))
  val bankConflictCounts = RegInit(VecInit(Seq.fill(cfg.maxBanks)(0.U(32.W))))

  val newReference = Wire(Decoupled(new BankConflictReference(cfg)))
  newReference.valid := transactionQueue.io.deq.valid
  newReference.bits.reference := transactionQueue.io.deq.bits
  val marginalCycles = latency + VecInit(bankBusyCycles)(transactionQueue.io.deq.bits.bankAddr)
  newReference.bits.cycle := tCycle(cfg.maxLatencyBits-1, 0) + marginalCycles
  newReference.bits.done := marginalCycles === 0.U
  transactionQueue.io.deq.ready := newReference.ready

  val refBuffer = CollapsingBuffer(newReference, cfg.maxReads + cfg.maxWrites)
  val refList = refBuffer.io.entries
  val refUpdates = refBuffer.io.updates

  bankBusyCycles.zip(bankConflictCounts).zipWithIndex.foreach({ case ((busyCycles, conflictCount), idx) =>
    when(busyCycles > 0.U){
      busyCycles := busyCycles - 1.U
    }

    when(newReference.fire && newReference.bits.reference.bankAddr === idx.U){
      busyCycles := marginalCycles + conflictPenalty
      conflictCount := Mux(busyCycles > 0.U, conflictCount + 1.U, conflictCount)
    }
  })

  // Mark the reference as complete
  refList.zip(refUpdates).foreach({ case (ref, update) =>
    when(tCycle(cfg.maxLatencyBits-1, 0) === ref.bits.cycle) { update.bits.done := true.B }
  })

  val selector =  Module(new Arbiter(refList.head.bits.cloneType, refList.size))
  selector.io.in <> refList.map({ entry =>
      val candidate = V2D(entry)
      candidate.valid := entry.valid && entry.bits.done
      candidate
    })

  // Take the readies from the arbiter, and kill the selected entry
  refUpdates.zip(selector.io.in).foreach({ case (ref, sel) =>
    when(sel.fire) { ref.valid := false.B } })

  io.mmReg.bankConflicts := bankConflictCounts

  val completedRef = selector.io.out.bits.reference

  rResp.bits := ReadResponseMetaData(completedRef.xaction)
  wResp.bits := WriteResponseMetaData(completedRef.xaction)
  wResp.valid := selector.io.out.valid && completedRef.xaction.isWrite
  rResp.valid := selector.io.out.valid && !completedRef.xaction.isWrite
  selector.io.out.ready := Mux(completedRef.xaction.isWrite, wResp.ready, rResp.ready)
}
