package midas
package models

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import junctions._
import midas.widgets._

/** A simple freelist
  * @param entries The number of IDS to be managed by the free list
  *
  * Inputs: freeId. Valid is asserted along side an ID that is to be
  *         returned to the freelist
  *
  * Outputs: nextId. The next available ID. Granted on a successful handshake
  */

class FreeList(entries: Int) extends Module {
  val io = IO(new Bundle {
    val freeId = Flipped(Valid(UInt(log2Up(entries).W)))
    val nextId = Decoupled(UInt(log2Up(entries).W))
  })
  require(entries > 0)
  val nextId = RegInit({ val i = Wire(Valid(UInt())); i.valid := true.B;
                            i.bits := 0.U; i})

  io.nextId.valid := nextId.valid
  io.nextId.bits := nextId.bits
  // Add an extra entry to represent the empty bit. Maybe not necessary?
  val ids = RegInit(VecInit(Seq.tabulate(entries)(i =>
    if (i == 0) false.B else true.B)))
  val next = ids.indexWhere((x:Bool) => x)

  when(io.nextId.fire || ~nextId.valid) {
    nextId.bits := next
    nextId.valid := ids.exists((x: Bool) => x)
    ids(next) := false.B
  }

  when(io.freeId.valid) {
    ids(io.freeId.bits) := true.B
  }
}

// This maintains W-W R-R orderings by managing a set of shared physical
// queues based on the the NASTI id field.
class RATEntry(vIdWidth: Int, pIdWidth: Int) extends Bundle {
  val current = Valid(UInt(vIdWidth.W))
  val next = Valid(UInt(pIdWidth.W))
  val head = Output(Bool())

  def matchHead(id: UInt): Bool = {
    (current.bits === id) && head
  }

  def matchTail(id: UInt): Bool = {
    (current.bits === id) && (current.valid) && !next.valid
  }
  def push(id: UInt): Unit = {
    next.bits := id
    next.valid := true.B
  }
  def setTranslation(id: UInt): Unit = {
    current.bits := id
    current.valid := true.B
  }

  def setHead(): Unit = { head := true.B }
  def pop(): Unit = {
    current.valid := false.B
    next.valid := false.B
    head := false.B
  }
}

object RATEntry {
  def apply(vIdWidth: Int, pIdWidth: Int) = {
    val entry = Wire(new RATEntry(vIdWidth, pIdWidth))
    entry.current.valid := false.B
    entry.current.bits := DontCare
    entry.next.valid := false.B
    entry.next.bits := DontCare
    entry.head := false.B
    entry
  }
}

class AllocationIO(vIdWidth: Int, pIdWidth: Int) extends Bundle {
  val pId = Output(UInt(pIdWidth.W))
  val vId = Input(UInt(vIdWidth.W))
  val ready = Output(Bool())
  val valid = Input(Bool())

  def fire: Bool = ready && valid
}


class ReorderBuffer(val numVIds: Int, val numPIds: Int) extends Module {
  val pIdWidth = log2Up(numPIds)
  val vIdWidth = log2Up(numVIds)
  val io = IO(new Bundle {
    // Free a physical ID
    val free = Flipped(Valid(UInt(pIdWidth.W)))
    // ID Allocation. Two way handshake. The next available PId is held on
    // nextPId.bits. nextPID.valid == false if there are no free IDs avaiable.
    // Allocation occurs when nextPId.fire asserts
    val next = new AllocationIO(vIdWidth, pIdWidth)
    val trans = new AllocationIO(vIdWidth, pIdWidth)
  })

  val rat = RegInit(VecInit(Seq.fill(numPIds)(RATEntry(vIdWidth, pIdWidth))))
  val freeList = Module(new FreeList(numPIds))
  freeList.io.freeId <> io.free

  // PID allocation
  io.next.ready := freeList.io.nextId.valid
  freeList.io.nextId.ready := io.next.valid
  val nextPId = freeList.io.nextId.bits
  io.next.pId := nextPId

  // Pointer to the child of an entry being freed (it will become the new head)
  val nextHeadPtr = WireInit({val w = Wire(Valid(UInt(pIdWidth.W))); w.valid := false.B; w.bits := DontCare; w})

  // Pointer to the parent of a entry being appended to a linked-list
  val parentEntryPtr = Wire(Valid(UInt()))
  parentEntryPtr.bits := rat.onlyIndexWhere(_.matchTail(io.next.vId))
  parentEntryPtr.valid := io.next.fire && rat.exists(_.matchTail(io.next.vId))


  for((entry,index) <- rat.zipWithIndex){
    // Allocation: Set the pointer of the new entry's parent
    when(parentEntryPtr.valid && parentEntryPtr.bits === index.U){
      rat(parentEntryPtr.bits).push(nextPId)
    }
    // Deallocation: Set the head bit of a link list whose head is to be freed
    when(nextHeadPtr.valid && (index.U === nextHeadPtr.bits)) {
      rat(index).setHead()
    }
    // Allocation: Add the new entry to the table
    when(io.next.fire && nextPId === index.U) {
      rat(index).setTranslation(io.next.vId)
      // We set the head bit if no linked-list exists for this vId, or
      // if the parent, and thus previous head, is about to be freed.
      when (~parentEntryPtr.valid ||
            (io.trans.fire && (io.trans.pId === parentEntryPtr.bits))){
        rat(index).setHead()
      }
    }
    // Deallocation: invalidate the entry = io.free.bits
    // Note this exploits last connect semantics to override the pushing
    // of new child to this entry when it is about to be freed.
    when(io.trans.fire && (index.U === io.trans.pId)) {
      assert(rat(index).head)
      nextHeadPtr := rat(index).next
      rat(index).pop()
    }
  }

  io.trans.pId := rat.onlyIndexWhere(_.matchHead(io.trans.vId))
  io.trans.ready := rat.exists(_.matchHead(io.trans.vId))
}


// Read response staging units only buffer data and last fields of a B payload
class StoredBeat(implicit p: Parameters) extends NastiBundle()(p) with HasNastiData

// Buffers read reponses from the host-memory system in a structure that maintains
// their per-transaction ID ordering.
class ReadEgressResponseIO(implicit p: Parameters) extends NastiBundle()(p) {
  val tBits = Output(new NastiReadDataChannel)
  val tReady = Input(Bool()) // Really this is part of the input token to the egress unit...
  val hValid = Output(Bool())
}

class ReadEgressReqIO(implicit p: Parameters) extends NastiBundle()(p) {
  val t = Output(Valid(UInt(p(NastiKey).idBits.W)))
  val hValid = Output(Bool())
}

class ReadEgress(maxRequests: Int, maxReqLength: Int, maxReqsPerId: Int)
    (implicit val p: Parameters) extends Module {
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(new NastiReadDataChannel))
    val resp = new ReadEgressResponseIO
    val req = Flipped(new ReadEgressReqIO)
  })

  // The total BRAM state required to implement a maximum length queue for each AXI transaction ID
  val virtualState = (maxReqsPerId * (1 << p(NastiKey).idBits) * maxReqLength * p(NastiKey).dataBits)
  // The total BRAM state required to dynamically allocate a entres to responses
  val physicalState = (maxRequests  * maxReqLength * p(NastiKey).dataBits)
  // 0x20000 = 4 32 Kb BRAMs
  val generateTranslation = (virtualState > 0x20000) && (virtualState > physicalState + 0x10000)

  // This module fires whenever there is a token available on the request port.
  val targetFire = io.req.hValid

  // On reset, the egress unit always has a single output token valid, but with invalid target data
  val currReqReg = RegInit({
    val r = Wire(io.req.t.cloneType)
    r.valid := false.B
    r.bits := DontCare
    r
  })

  val xactionDone = Wire(Bool())
  when (targetFire && io.req.t.valid) {
    currReqReg := io.req.t
  }.elsewhen (targetFire && xactionDone) {
    currReqReg.valid := false.B
  }

  val xactionStart = targetFire && io.req.t.valid
  // Queue address into which to enqueue the host-response
  val enqPId = Wire(Valid(UInt()))
  // Queue address from which to dequeue the response
  val (deqPId: UInt, deqPIdReg: ValidIO[UInt]) = if (generateTranslation) {
    val rob = Module(new ReorderBuffer(1 << p(NastiKey).idBits, maxRequests))
    val enqPIdReg = RegInit({val i = Wire(Valid(UInt(log2Up(maxRequests).W)))
                              i.valid := false.B;
                              i.bits := DontCare;
                              i})

    val deqPIdReg = RegInit({ val r = Wire(Valid(UInt(log2Up(maxRequests).W)));
                              r.valid := false.B;
                              r.bits := DontCare;
                              r })
    val translationFailure = currReqReg.valid && ~deqPIdReg.valid

    rob.io.trans.vId := Mux(translationFailure, currReqReg.bits, io.req.t.bits)
    rob.io.trans.valid := translationFailure || xactionStart
    rob.io.free.valid := xactionDone
    rob.io.free.bits := deqPIdReg.bits

    when(rob.io.trans.fire) {
      deqPIdReg.valid := rob.io.trans.fire
      deqPIdReg.bits := rob.io.trans.pId
    }.elsewhen (targetFire && xactionDone) {
      deqPIdReg.valid := false.B
    }


    //Don't initiate another allocation until the current one has finished
    rob.io.next.vId := io.enq.bits.id
    io.enq.ready := enqPId.valid
    assert(enqPId.valid || ~io.enq.valid)
    rob.io.next.valid := ~enqPIdReg.valid && io.enq.valid
    enqPId.bits := Mux(enqPIdReg.valid, enqPIdReg.bits, rob.io.next.pId)
    enqPId.valid := enqPIdReg.valid || rob.io.next.ready
    when (io.enq.fire) {
      when (io.enq.bits.last) {
        enqPIdReg.valid := false.B
      }.elsewhen (~enqPIdReg.valid) {
        enqPIdReg.valid := true.B
        enqPIdReg.bits := rob.io.next.pId
      }
    }
    // Deq using the translation if first beat, otherwise use the register
    val deqPId = Mux(translationFailure || xactionStart, rob.io.trans.pId, deqPIdReg.bits)
    (deqPId, deqPIdReg)
  } else {
    enqPId.bits := io.enq.bits.id
    enqPId.valid := io.enq.valid
    io.enq.ready := true.B
    val deqPId = Mux(xactionStart, io.req.t.bits, currReqReg.bits)
    (deqPId, currReqReg)
  }

  val mQDepth = if (generateTranslation) maxReqLength else maxReqLength * maxReqsPerId
  val mQWidth = if (generateTranslation) maxRequests else 1 << p(NastiKey).idBits
  val multiQueue = Module(new MultiQueue(new StoredBeat, mQWidth, mQDepth))

  multiQueue.io.enq.bits.data := io.enq.bits.data
  multiQueue.io.enq.bits.last := io.enq.bits.last
  multiQueue.io.enq.valid := io.enq.valid
  multiQueue.io.enqAddr := enqPId.bits
  multiQueue.io.deqAddr := deqPId

  xactionDone := targetFire && currReqReg.valid && deqPIdReg.valid &&
                 io.resp.tReady && io.resp.tBits.last

  io.resp.tBits := NastiReadDataChannel(currReqReg.bits,
    multiQueue.io.deq.bits.data, multiQueue.io.deq.bits.last)
  io.resp.hValid := ~currReqReg.valid || (deqPIdReg.valid && multiQueue.io.deq.valid)
  multiQueue.io.deq.ready := targetFire && currReqReg.valid &&
    deqPIdReg.valid && io.resp.tReady
}

class WriteEgressResponseIO(implicit p: Parameters) extends NastiBundle()(p) {
  val tBits = Output(new NastiWriteResponseChannel)
  val tReady = Input(Bool())
  val hValid = Output(Bool())
}

class WriteEgressReqIO(implicit p: Parameters) extends NastiBundle()(p) {
  val t = Output(Valid(UInt(p(NastiKey).idBits.W)))
  val hValid = Output(Bool())
}
// Maintains a series of incrementer/decrementers to track the number of
// write acknowledgements returned by the host memory system. No other
// response metadata is stored.
class WriteEgress(maxRequests: Int, maxReqLength: Int, maxReqsPerId: Int)
    (implicit val p: Parameters) extends Module {
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(new NastiWriteResponseChannel))
    val resp = new WriteEgressResponseIO
    val req = Flipped(new WriteEgressReqIO)
  })

  // This module fires whenever there is a token available on the request port.
  val targetFire = io.req.hValid

  // Indicates whether the egress unit is releasing a transaction
  val currReqReg = RegInit({
    val r = Wire(io.req.t.cloneType)
    r.valid := false.B
    r.bits := DontCare
    r
  })
  val haveAck = RegInit(false.B)
  when (targetFire && io.req.t.valid) {
    currReqReg := io.req.t
  }.elsewhen (targetFire && currReqReg.valid && haveAck && io.resp.tReady) {
    currReqReg.valid := false.B
  }

  val ackCounters = Seq.fill(1 << p(NastiKey).idBits)(RegInit(0.U(log2Up(maxReqsPerId + 1).W)))
  val notEmpty = VecInit(ackCounters map {_ =/= 0.U})
  val retry = currReqReg.valid && !haveAck
  val deqId = Mux(retry, currReqReg.bits, io.req.t.bits)
  when (retry || targetFire && io.req.t.valid) {
    haveAck := notEmpty(deqId)
  }

  val idMatch = currReqReg.bits === io.enq.bits.id
  val do_enq = io.enq.fire
  val do_deq = targetFire && currReqReg.valid && haveAck && io.resp.tReady
  ackCounters.zipWithIndex foreach { case (count, idx) =>
    when (!(do_deq && do_enq && idMatch)) {
      when(do_enq && io.enq.bits.id === idx.U) {
        count := count + 1.U
      }.elsewhen(do_deq && currReqReg.bits === idx.U) {
        count := count - 1.U
      }
    }
  }

  io.resp.tBits := NastiWriteResponseChannel(currReqReg.bits)
  io.resp.hValid := !currReqReg.valid || haveAck
  io.enq.ready := true.B
}

trait EgressUnitParameters {
  val egressUnitDelay = 1
}

