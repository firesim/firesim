package midas
package models

import chisel3._
import chisel3.util._
import junctions._
import midas.widgets._

import freechips.rocketchip.config.Parameters
import freechips.rocketchip.util.DecoupledHelper

// Add some scheduler specific metadata to a reference
class XactionSchedulerEntry(implicit p: Parameters) extends NastiBundle()(p) {
  val xaction = new TransactionMetaData
  val addr = UInt(nastiXAddrBits.W)
 }

class XactionSchedulerIO(val cfg: BaseConfig)(implicit val p: Parameters) extends Bundle{
  val req = Flipped(new NastiReqChannels)
  val nextXaction = Decoupled(new XactionSchedulerEntry)
  val pendingWReq = Input(UInt((cfg.maxWrites + 1).W))
  val pendingAWReq = Input(UInt((cfg.maxWrites + 1).W))
}

class UnifiedFIFOXactionScheduler(depth: Int, cfg: BaseConfig)(implicit p: Parameters) extends Module {
  val io = IO(new XactionSchedulerIO(cfg))

  import DRAMMasEnums._

  val transactionQueue = Module(new Queue(new XactionSchedulerEntry, depth))
  val transactionQueueArb = Module(new RRArbiter(new XactionSchedulerEntry, 2))

  transactionQueueArb.io.in(0).valid := io.req.ar.valid
  io.req.ar.ready := transactionQueueArb.io.in(0).ready
  transactionQueueArb.io.in(0).bits.xaction := TransactionMetaData(io.req.ar.bits)
  transactionQueueArb.io.in(0).bits.addr := io.req.ar.bits.addr

  transactionQueueArb.io.in(1).valid := io.req.aw.valid
  io.req.aw.ready := transactionQueueArb.io.in(1).ready
  transactionQueueArb.io.in(1).bits.xaction := TransactionMetaData(io.req.aw.bits)
  transactionQueueArb.io.in(1).bits.addr := io.req.aw.bits.addr

  transactionQueue.io.enq <> transactionQueueArb.io.out

  // Accept up to one additional write data request
  // TODO: More sensible model; maybe track a write buffer volume
  io.req.w.ready := io.pendingWReq <= io.pendingAWReq

  val selectedCmd = WireInit(cmd_nop)
  val completedWrites = SatUpDownCounter(cfg.maxWrites)
  completedWrites.inc := io.req.w.fire && io.req.w.bits.last
  completedWrites.dec := io.nextXaction.fire && io.nextXaction.bits.xaction.isWrite

  // Prevent release of oldest transaction if it is a write and it's data is not yet available
  val deqGate = DecoupledHelper(
    transactionQueue.io.deq.valid,
    io.nextXaction.ready,
    (!io.nextXaction.bits.xaction.isWrite || ~completedWrites.empty)
  )

  io.nextXaction <> transactionQueue.io.deq
  io.nextXaction.valid := deqGate.fire(io.nextXaction.ready)
  transactionQueue.io.deq.ready := deqGate.fire(transactionQueue.io.deq.valid)
}
