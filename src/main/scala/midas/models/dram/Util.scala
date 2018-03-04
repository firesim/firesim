package midas
package models

// From RC
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.util.{ParameterizedBundle, GenericParameterizedBundle}
import junctions._

import chisel3._
import chisel3.util._

// From MIDAS
import midas.widgets.{D2V, V2D, SkidRegister}

class DualQueue[T <: Data](gen: =>T, entries: Int) extends Module {
  val io = IO(new Bundle {
    val enqA = Flipped(Decoupled(gen.cloneType))
    val enqB = Flipped(Decoupled(gen.cloneType))
    val deq = Decoupled(gen.cloneType)
    val next = Valid(gen.cloneType)
  })

  val qA = Module(new Queue(gen.cloneType, (entries+1)/2))
  val qB = Module(new Queue(gen.cloneType, entries/2))
  qA.io.deq.ready := false.B
  qB.io.deq.ready := false.B

  val enqPointer = RegInit(false.B)
  when (io.enqA.fire() ^ io.enqB.fire()) {
    enqPointer := ~enqPointer
  }

  when(enqPointer ^ ~io.enqA.valid){
    qA.io.enq <> io.enqB
    qB.io.enq <> io.enqA
  }.otherwise{
    qA.io.enq <> io.enqA
    qB.io.enq <> io.enqB
  }

  val deqPointer = RegInit(false.B)
  when (io.deq.fire()) {
    deqPointer := ~deqPointer
  }

  when(deqPointer){
    io.deq <> qB.io.deq
    io.next <> D2V(qA.io.deq)
  }.otherwise{
    io.deq <> qA.io.deq
    io.next <> D2V(qB.io.deq)
  }
}

class ProgrammableSubAddr(maskBits: Int, longName: String) extends Bundle with HasProgrammableRegisters{
  val offset = UInt(32.W) // TODO:fixme
  val mask = UInt(maskBits.W) // Must be contiguous high bits starting from LSB
  def getSubAddr(fullAddr: UInt): UInt = (fullAddr >> offset) & mask

  // Used to produce a bit vector of enables from a mask
  def maskToOH(): UInt = {
    val decodings = Seq.tabulate(maskBits)({ i => ((1 << (1 << (i + 1))) - 1).U})
    MuxCase(1.U, (mask.toBools.zip(decodings)).reverse)
  }

  val registers = Seq(
    (offset -> RuntimeSetting(8,s"${longName} Offset", min = 0)),
    (mask   -> RuntimeSetting(7,s"${longName} Mask", max = Some((1 << maskBits) - 1)))
  )

  def forceSettings(offsetValue: BigInt, maskValue: BigInt) {
    regMap(offset).set(offsetValue)
    regMap(mask).set(maskValue)
  }

  override def cloneType = new ProgrammableSubAddr(maskBits, longName).asInstanceOf[this.type]

}

// A common motif to track inputs in a buffer
trait HasFIFOPointers {
  val entries: Int
  val do_enq = Wire(Bool())
  val do_deq = Wire(Bool())

  val enq_ptr = Counter(entries)
  val deq_ptr = Counter(entries)
  val maybe_full = Reg(init=false.B)

  val ptr_match = enq_ptr.value === deq_ptr.value
  val empty = ptr_match && !maybe_full
  val full = ptr_match && maybe_full

  when (do_enq) {
    enq_ptr.inc()
  }
  when (do_deq) {
    deq_ptr.inc()
  }
  when (do_enq != do_deq) {
    maybe_full := do_enq
  }
}

class DynamicLatencyPipeIO[T <: Data](gen: T, entries: Int, countBits: Int)
    extends QueueIO(gen, entries) {
  val latency = Input(UInt(countBits.W))
  val tCycle = Input(UInt(countBits.W))

  override def cloneType = new DynamicLatencyPipeIO(gen, entries, countBits).asInstanceOf[this.type]
}

// I had to copy this code because critical fields are now private
class DynamicLatencyPipe[T <: Data] (
    gen: T,
    val entries: Int,
    countBits: Int
  ) extends Module with HasFIFOPointers {
  val io = IO(new DynamicLatencyPipeIO(gen, entries, countBits))

  val ram = Mem(entries, gen)
  do_enq := io.enq.fire()
  do_deq := io.deq.fire()

  when (do_enq) {
    ram(enq_ptr.value) := io.enq.bits
  }

  io.enq.ready := !full
  io.deq.bits := ram(deq_ptr.value)

  val ptr_diff = enq_ptr.value - deq_ptr.value
  if (isPow2(entries)) {
    io.count := Cat(maybe_full && ptr_match, ptr_diff)
  } else {
    io.count := Mux(ptr_match,
                    Mux(maybe_full,
                      entries.asUInt, 0.U),
                    Mux(deq_ptr.value > enq_ptr.value,
                      entries.asUInt + ptr_diff, ptr_diff))
  }

  val latencies = Reg(Vec(entries, UInt(countBits.W)))
  val pending = RegInit(Vec.fill(entries)(false.B))
  latencies.zip(pending) foreach { case (lat, pending) =>
    when (lat === io.tCycle) { pending := false.B }
  }

  when (do_enq && io.latency > 1.U) {
    latencies(enq_ptr.value) := io.tCycle + io.latency
    pending(enq_ptr.value) := true.B
  }

  io.deq.valid := !empty && !pending(deq_ptr.value)
}

// Counts down from a set value; If the set value is less than the present value 
// it is ignored.

class DownCounter(counterWidth: Int) extends Module {
  val io = IO(new Bundle {
    val set = Input(Valid(UInt(counterWidth.W)))
    val decr = Input(Bool())
    val current = Output(UInt(counterWidth.W))
    val idle = Output(Bool())
  })

  require(counterWidth > 0, "DownCounter must have a width > 0")
  val delay = RegInit(0.U(counterWidth.W))
  when(io.set.valid && io.set.bits >= delay) {
    delay := io.set.bits
  }.elsewhen(io.decr && delay != 0.U){
    delay := delay - 1.U
  }
  io.idle := delay === 0.U
  io.current := delay
}

// While down counter has a local decrementer, this module instead matches against
// a provided cycle count.
class CycleTracker(counterWidth: Int) extends Module {
  val io = IO(new Bundle {
    val set = Input(Valid(UInt(counterWidth.W)))
    val tCycle = Input(UInt(counterWidth))
    val idle = Output(Bool())
  })

  require(counterWidth > 0, "CycleTracker must have a width > 0")
  val delay = RegInit(0.U(counterWidth.W))
  val idle  = RegInit(true.B)
  when(io.set.valid && io.tCycle != io.set.bits) {
    delay := io.set.bits
    idle := false.B
  }.elsewhen(delay === io.tCycle){
    idle := true.B
  }
  io.idle := idle
}

// A collapsing buffer with entries that can be updated. Valid entries trickle
// down through queue, one entry per cycle.
// Kill is implemented by setting io.update(entry).valid := false.B
//
// NB: Companion object should be used to generate a module instance -> or
// updates must be driven to entries by default for the module to behave
// correctly
class CollapsingBufferIO[T <: Data](gen: T, depth: Int) extends Bundle {
  val entries = Output(Vec(depth, Valid(gen.cloneType)))
  val updates = Input(Vec(depth, Valid(gen.cloneType)))
  val enq = Flipped(Decoupled(gen.cloneType))
  val programmableDepth = Input(UInt(log2Ceil(depth+1).W))
}

// Note: Use companion object
class CollapsingBuffer[T <: Data](gen: T, depth: Int) extends Module {
  val io = IO(new CollapsingBufferIO(gen, depth))

  def linkEntries(entries: Seq[(ValidIO[T], ValidIO[T], Bool)], shifting: Bool): Unit = entries match {
    case Nil => throw new RuntimeException("Asked for 0 entry collapasing buffer?")
    // Youngest entry, connect up io.enq
    case (entry, currentUpdate, lastEntry) :: Nil => {
      val shift = shifting || !currentUpdate.valid
      entry := Mux(shift, D2V(io.enq), currentUpdate)
      io.enq.ready := shift
    }
    // Default case, a younger stage enqueues into this one
    case (entry, currentUpdate, lastEntry) :: tail => {
      val youngerUpdate = tail.head._2
      val shift = !lastEntry && ( shifting || !currentUpdate.valid)
      entry := Mux(shift, youngerUpdate, currentUpdate)
      linkEntries(tail, shift)
    }
  }

  val lastEntry = UIntToOH(io.programmableDepth).toBools.take(depth).reverse
  val entries = Seq.fill(depth)(
    RegInit({val w = Wire(Valid(gen.cloneType)); w.valid := false.B; w.bits := DontCare; w}))
  io.entries := entries

  linkEntries((entries, io.updates, lastEntry).zipped.toList, false.B)
}

object CollapsingBuffer {
  def apply[T <: Data](
      enq: DecoupledIO[T],
      depth: Int,
      programmableDepth: Option[UInt] = None): CollapsingBuffer[T] = {

    val buffer = Module(new CollapsingBuffer(enq.bits, depth))
    // This sets the default that each entry retains its value unless driven by the parent mod
    (buffer.io.updates).zip(buffer.io.entries).foreach({ case (e, u) =>  e := u })
    buffer.io.enq <> enq
    buffer.io.programmableDepth := programmableDepth.getOrElse(depth.U)
    buffer
  }
}

trait HasAXI4Id extends HasNastiParameters { val id = UInt(nastiXIdBits.W) }
trait HasAXI4IdAndLen extends HasAXI4Id { val len = UInt(nastiXLenBits.W) }
trait HasReqMetaData extends HasAXI4IdAndLen { val addr = UInt(nastiXAddrBits.W) }

class TransactionMetaData(implicit val p: Parameters) extends Bundle with HasAXI4IdAndLen {
  val isWrite = Bool()
}

object TransactionMetaData {
  def apply(id: UInt, len: UInt, isWrite: Bool)(implicit p: Parameters): TransactionMetaData = {
    val w = Wire(new TransactionMetaData)
    w.id := id
    w.len := len
    w.isWrite := isWrite
    w
  }

  def apply(x: NastiReadAddressChannel)(implicit p: Parameters): TransactionMetaData = 
    apply(x.id, x.len, false.B)

  def apply(x: NastiWriteAddressChannel)(implicit p: Parameters): TransactionMetaData = 
    apply(x.id, x.len, true.B)

}

class WriteResponseMetaData(implicit val p: Parameters) extends Bundle with HasAXI4Id
class ReadResponseMetaData(implicit val p: Parameters) extends Bundle with HasAXI4IdAndLen

object ReadResponseMetaData {
  def apply(x: HasAXI4IdAndLen)(implicit p: Parameters): ReadResponseMetaData = {
    val readMetaData = Wire(new ReadResponseMetaData)
    readMetaData.id := x.id
    readMetaData.len := x.len
    readMetaData
  }
  // UGH. Will fix when i go to RC's AXI4 impl
  def apply(x: NastiReadAddressChannel)(implicit p: Parameters): ReadResponseMetaData = {
    val readMetaData = Wire(new ReadResponseMetaData)
    readMetaData.id := x.id
    readMetaData.len := x.len
    readMetaData
  }
}

object WriteResponseMetaData {
  def apply(x: HasAXI4Id)(implicit p: Parameters): WriteResponseMetaData = {
    val writeMetaData = Wire(new WriteResponseMetaData)
    writeMetaData.id := x.id
    writeMetaData
  }

  def apply(x: NastiWriteAddressChannel)(implicit p: Parameters): WriteResponseMetaData = {
    val writeMetaData = Wire(new WriteResponseMetaData)
    writeMetaData.id := x.id
    writeMetaData
  }
}

class AXI4ReleaserIO(implicit p: Parameters) extends ParameterizedBundle()(p) {
  val b = Decoupled(new NastiWriteResponseChannel)
  val r = Decoupled(new NastiReadDataChannel)
  val egressReq = new EgressReq
  val egressResp = Flipped(new EgressResp)
  val nextRead = Flipped(Decoupled(new ReadResponseMetaData))
  val nextWrite = Flipped(Decoupled(new WriteResponseMetaData))
}


class AXI4Releaser(implicit p: Parameters) extends Module {
  val io = IO(new AXI4ReleaserIO)

  val currentRead = Queue(io.nextRead, 1, pipe = true)
  currentRead.ready := io.r.fire && io.r.bits.last
  io.egressReq.r.valid := io.nextRead.fire
  io.egressReq.r.bits := io.nextRead.bits.id
  io.r.valid := currentRead.valid
  io.r.bits := io.egressResp.rBits
  io.egressResp.rReady := io.r.ready

  val currentWrite = Queue(io.nextWrite, 1, pipe = true)
  currentWrite.ready := io.b.fire
  io.egressReq.b.valid := io.nextWrite.fire
  io.egressReq.b.bits := io.nextWrite.bits.id
  io.b.valid := currentWrite.valid
  io.b.bits := io.egressResp.bBits
  io.egressResp.bReady := io.b.ready
}

class FIFOAddressMatcher(val entries: Int, addrWidth: Int) extends Module with HasFIFOPointers {
  val io  = IO(new Bundle {
    val enq = Flipped(Valid(UInt(addrWidth.W)))
    val deq = Input(Bool())
    val match_address = Input(UInt(addrWidth.W))
    val hit = Output(Bool())
  })

  val addrs = RegInit(Vec.fill(entries)({ val w = Wire(Valid(UInt(addrWidth.W))); w.valid := false.B; w }))
  do_enq := io.enq.valid
  do_deq := io.deq

  assert(!full || (!do_enq || do_deq)) // Since we don't have backpressure, check for overflow
  when (do_enq) {
    addrs(enq_ptr.value).valid := true.B
    addrs(enq_ptr.value).bits := io.enq.bits
  }

  when (do_deq) {
    addrs(deq_ptr.value).valid := false.B
  }

  io.hit := addrs.exists({entry =>  entry.valid && entry.bits === io.match_address })
}

class AddressCollisionCheckerIO(addrWidth: Int)(implicit p: Parameters) extends NastiBundle()(p) {
  val read_req = Input(Valid(UInt(addrWidth.W)))
  val read_done = Input(Bool())
  val write_req = Input(Valid(UInt(addrWidth.W)))
  val write_done = Input(Bool())
  val collision_addr = ValidIO(UInt(addrWidth.W))
}

class AddressCollisionChecker(numReads: Int, numWrites: Int, addrWidth: Int)(implicit p: Parameters)
    extends NastiModule()(p) {
  val io = IO(new AddressCollisionCheckerIO(addrWidth))

  require(isPow2(numReads))
  require(isPow2(numWrites))
  //val discardedLSBs = 6
  //val addrType = UInt(p(NastiKey).addrBits - discardedLSBs)

  val read_matcher = Module(new FIFOAddressMatcher(numReads, addrWidth)).io
  read_matcher.enq := io.read_req
  read_matcher.deq := io.read_done
  read_matcher.match_address := io.write_req.bits

  val write_matcher = Module(new FIFOAddressMatcher(numReads, addrWidth)).io
  write_matcher.enq := io.write_req
  write_matcher.deq := io.write_done
  write_matcher.match_address := io.read_req.bits

  io.collision_addr.valid := io.read_req.valid && write_matcher.hit ||
                             io.write_req.valid && read_matcher.hit
  io.collision_addr.bits := Mux(io.read_req.valid, io.read_req.bits, io.write_req.bits)
}

object AddressCollisionCheckMain extends App {
  implicit val p = Parameters.empty.alterPartial({case NastiKey => NastiParameters(64,32,4)})
  chisel3.Driver.execute(args, () => new AddressCollisionChecker(4,4,16))
}
