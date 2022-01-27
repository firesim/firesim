package midas
package models

// From RC
import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.util.{DecoupledHelper}
import junctions._

import chisel3._
import chisel3.util.{Queue, Decoupled}

import midas.core.{HostDecoupled}
import midas.widgets.{SatUpDownCounter}

// The ingress module queues up incoming target requests, and issues them to the
// host memory system.

// NB: The AXI4 imposes no ordering between in flight reads and writes. In the
// event the target-master issues a read and a write to an overlapping memory
// region, host-memory-system reorderings of those requests will result in
// non-deterministic target behavior.
//
// asserting io.relaxed = true, allows the ingress unit to issue requests ASAP. This
// is a safe optimization only for non-chump-city AXI4 masters.
//
// asserting io.relaxed = false, will force the ingress unit to pessimistically
// issue host-memory requests to prevent reorderings,by waiting for the
// host-memory system to go idle before 1) issuing any write, 2) issuing a read
// if there is a write inflight. (I did not want to add the extra complexity of
// tracking inflight addresses.) This has the effect of forcing reads to see
// the value of youngest write for which the AW and all W beats have been
// accepted, but no write acknowledgement has been issued..

trait IngressModuleParameters {
  val cfg: BaseConfig
  implicit val p: Parameters
  // In general the only consequence of undersizing these are more wasted
  // host cycles the model waits to drain these
  val ingressAWQdepth = cfg.maxWrites
  val ingressWQdepth = 2*cfg.maxWriteLength
  val ingressARQdepth = 4

  // DEADLOCK RISK: if the host memory system accepts only one AW while a W
  // xaction is inflight, and the entire W-transaction is not available in the
  // ingress module the host memory system will drain the WQueue without
  // consuming another AW token. The target will remain stalled and cannot
  // complete the W xaction.
  require(ingressWQdepth >= cfg.maxWriteLength)
  require(ingressAWQdepth >= cfg.maxWrites)
}

class IngressModule(val cfg: BaseConfig)(implicit val p: Parameters) extends Module 
    with IngressModuleParameters {
  val io = IO(new Bundle {
    // This is target valid and not decoupled because the model has handshaked
    // the target-level channels already for us
    val nastiInputs = Flipped(HostDecoupled((new ValidNastiReqChannels)))
    val nastiWRespInputs = Flipped(Decoupled(new NastiWriteResponseChannel)) 
    val nastiRRespInputs = Flipped(Decoupled(new NastiReadDataChannel)) 
    val nastiOutputs = new NastiReqChannels
    val nastiRespOutputs = new NastiRespChannels 
    val relaxed = Input(Bool())
    val host_mem_idle = Input(Bool())
    val host_read_inflight = Input(Bool())
  })


  val awQueue = Module(new Queue(new NastiWriteAddressChannel, ingressAWQdepth))
  val wQueue  = Module(new Queue(new NastiWriteDataChannel, ingressWQdepth))
  val arQueue = Module(new Queue(new NastiReadAddressChannel, ingressARQdepth))

  // Host request gating -- wait until we have a complete W transaction before
  // we issue it.
  val wCredits = SatUpDownCounter(cfg.maxWrites)
  wCredits.inc := awQueue.io.enq.fire
  wCredits.dec := wQueue.io.deq.fire && wQueue.io.deq.bits.last
  val awCredits = SatUpDownCounter(cfg.maxWrites)
  awCredits.inc := wQueue.io.enq.fire && wQueue.io.enq.bits.last
  awCredits.dec := awQueue.io.deq.fire

  //TODO also have to maintain awQueue address queue for read address matcher
  val nastiInputs = new NastiReqChannels 
  val addrWidth = nastiInputs.aw.bits.nastiXAddrBits 
  val addrLength = nastiInputs.aw.bits.nastiXLenBits
  val idWidth = nastiInputs.aw.bits.id


  //These consists of transactions in flight with host-DRAM. The number of entries
  //are 2x to accommodate the requests that are not yet dequeued to host-DRAM and
  //that are in host-DRAM. These structures store IDs of transactions, sent to host-DRAM
  //and the responses are piggybacked with IDs in the same order.
  val arIDQueue = Module(new Queue(idWidth, cfg.maxReads*2))
  val awIDQueue = Module(new Queue(idWidth, cfg.maxWrites*2))

  // All the sources of host stalls
  val tFireHelper = DecoupledHelper(
    io.nastiInputs.hValid,
    awQueue.io.enq.ready,
    wQueue.io.enq.ready,
    arQueue.io.enq.ready,
    arIDQueue.io.enq.ready,
    awIDQueue.io.enq.ready)


  val ingressUnitStall = !tFireHelper.fire(io.nastiInputs.hValid)

  // A request is finished when we have both a complete AW and W request
  // Only then can we consider issuing the write to host memory system
  //
  // When we aren't relaxing the ordering, we repurpose the credit counters to
  // simply count the number of complete W and AW requests.
  val write_req_done = ((awCredits.value > wCredits.value) && wCredits.inc) ||
                       ((awCredits.value < wCredits.value) && awCredits.inc) ||
                        awCredits.inc && wCredits.inc

  val read_req_done = arQueue.io.enq.fire
  when (!io.relaxed) {
    Seq(awCredits, wCredits) foreach { _.dec := write_req_done }
  }

  val readMatcher = Module(new FIFOAlignedAddressMatcher(cfg.maxReads*2, addrWidth, addrLength)).io
  readMatcher.enq.valid := arIDQueue.io.enq.fire
  readMatcher.enq.bits.addr := io.nastiInputs.hBits.ar.bits.addr
  readMatcher.enq.bits.len := io.nastiInputs.hBits.ar.bits.len
  readMatcher.match_address.addr := io.nastiInputs.hBits.aw.bits.addr
  readMatcher.match_address.len := io.nastiInputs.hBits.aw.bits.len

  val writeMatcher = Module(new FIFOAlignedAddressMatcher(cfg.maxWrites*2, addrWidth, addrLength)).io
  writeMatcher.enq.valid := awIDQueue.io.enq.fire 
  writeMatcher.enq.bits.addr := io.nastiInputs.hBits.aw.bits.addr
  writeMatcher.enq.bits.len := io.nastiInputs.hBits.aw.bits.len
  writeMatcher.match_address.addr := io.nastiInputs.hBits.ar.bits.addr
  writeMatcher.match_address.len := io.nastiInputs.hBits.ar.bits.len

  arIDQueue.io.enq.valid := tFireHelper.fire(arIDQueue.io.enq.ready) && io.nastiInputs.hBits.ar.valid
  arIDQueue.io.enq.bits := io.nastiInputs.hBits.ar.bits.id
  awIDQueue.io.enq.valid := tFireHelper.fire(awIDQueue.io.enq.ready) && io.nastiInputs.hBits.aw.valid
  awIDQueue.io.enq.bits := io.nastiInputs.hBits.aw.bits.id
  // FIFO that tracks the relative order of reads and writes are they are received 
  // bit 0 = Read, bit 1 = Write
  val xaction_order = Module(new DualQueue(Bool(), cfg.maxReads + cfg.maxWrites))
  val xaction_order_collisions = Module(new DualQueue(Bool(), cfg.maxReads + cfg.maxWrites))
  xaction_order.io.enqA.bits := true.B
  xaction_order.io.enqB.bits := false.B
  xaction_order_collisions.io.enqA.valid := read_req_done
  xaction_order_collisions.io.enqA.bits := writeMatcher.hit
  xaction_order_collisions.io.enqB.valid := write_req_done
  xaction_order_collisions.io.enqB.bits := readMatcher.hit

  val writeCollisions = SatUpDownCounter(cfg.maxWrites)

  val readCollisions = SatUpDownCounter(cfg.maxReads)

  writeCollisions.inc := awQueue.io.enq.fire && readMatcher.hit && io.nastiInputs.hBits.aw.valid 
  writeCollisions.dec := xaction_order.io.deq.valid && !xaction_order.io.deq.bits &&
                           xaction_order_collisions.io.deq.valid && xaction_order_collisions.io.deq.bits
  readCollisions.inc := arQueue.io.enq.fire && writeMatcher.hit && io.nastiInputs.hBits.ar.valid
  readCollisions.dec := xaction_order.io.deq.valid && xaction_order.io.deq.bits &&
                           xaction_order_collisions.io.deq.valid && xaction_order_collisions.io.deq.bits

  val noCollisions = writeCollisions.value === 0.U && readCollisions.value === 0.U

  xaction_order.io.enqA.valid := read_req_done 
  xaction_order.io.enqB.valid := write_req_done

  val do_hread = (io.relaxed && noCollisions) ||
    (io.host_mem_idle || io.host_read_inflight) && xaction_order.io.deq.valid && xaction_order.io.deq.bits

  val do_hwrite = Mux((io.relaxed && noCollisions), !awCredits.empty,
    io.host_mem_idle && xaction_order.io.deq.valid && !xaction_order.io.deq.bits)

  xaction_order.io.deq.ready := io.nastiOutputs.ar.fire || io.nastiOutputs.aw.fire
  xaction_order_collisions.io.deq.ready := io.nastiOutputs.ar.fire || io.nastiOutputs.aw.fire

  val do_hwrite_data_reg = RegInit(false.B)
  when (io.nastiOutputs.aw.fire) {
    do_hwrite_data_reg := true.B
  }.elsewhen (io.nastiOutputs.w.fire && io.nastiOutputs.w.bits.last) {
    do_hwrite_data_reg := false.B
  }

  val do_hwrite_data = Mux((io.relaxed && noCollisions), !wCredits.empty, do_hwrite_data_reg)


  io.nastiInputs.hReady := !ingressUnitStall

  arQueue.io.enq.bits := io.nastiInputs.hBits.ar.bits
  arQueue.io.enq.valid := tFireHelper.fire(arQueue.io.enq.ready) && io.nastiInputs.hBits.ar.valid

  io.nastiOutputs.ar <> arQueue.io.deq
  io.nastiOutputs.ar.valid := do_hread && arQueue.io.deq.valid
  io.nastiOutputs.ar.bits.id := 0.U 
  arQueue.io.deq.ready := do_hread && io.nastiOutputs.ar.ready

  awQueue.io.enq.bits := io.nastiInputs.hBits.aw.bits
  awQueue.io.enq.valid := tFireHelper.fire(awQueue.io.enq.ready) && io.nastiInputs.hBits.aw.valid
  wQueue.io.enq.bits := io.nastiInputs.hBits.w.bits
  wQueue.io.enq.valid := tFireHelper.fire(wQueue.io.enq.ready) && io.nastiInputs.hBits.w.valid

  io.nastiOutputs.aw.bits := awQueue.io.deq.bits
  io.nastiOutputs.aw.bits.id := 0.U 
  io.nastiOutputs.w.bits := wQueue.io.deq.bits

  io.nastiRespOutputs.r <> io.nastiRRespInputs
  io.nastiRespOutputs.b <> io.nastiWRespInputs
  io.nastiRespOutputs.b.bits.id := awIDQueue.io.deq.bits
  io.nastiRespOutputs.r.bits.id := arIDQueue.io.deq.bits
  awIDQueue.io.deq.ready := io.nastiWRespInputs.fire
  arIDQueue.io.deq.ready := io.nastiRRespInputs.fire && io.nastiRRespInputs.bits.last
  writeMatcher.deq := io.nastiWRespInputs.fire
  readMatcher.deq := io.nastiRRespInputs.fire && io.nastiRRespInputs.bits.last
  

  io.nastiOutputs.aw.valid := do_hwrite && awQueue.io.deq.valid
  awQueue.io.deq.ready := do_hwrite && io.nastiOutputs.aw.ready

  io.nastiOutputs.w.valid := do_hwrite_data && wQueue.io.deq.valid
  wQueue.io.deq.ready := do_hwrite_data && io.nastiOutputs.w.ready
  // Deadlock checks.
  assert(!(wQueue.io.enq.valid && !wQueue.io.enq.ready &&
           Mux(io.relaxed, wCredits.empty, !xaction_order.io.deq.valid)),
         "DEADLOCK: Timing model requests w enqueue, but wQueue is full and cannot drain")

  assert(!(awQueue.io.enq.valid && !awQueue.io.enq.ready &&
           Mux(io.relaxed, awCredits.empty, !xaction_order.io.deq.valid)),
         "DEADLOCK: Timing model requests aw enqueue, but is awQueue is full and cannot drain")
}
