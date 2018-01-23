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

// Adds a pipeline stage to a decoupled. Readys are tied together combinationally
object DecoupledPipeStage {
  def apply[T <: Data](in: DecoupledIO[T]): DecoupledIO[T] = {
    val reg = RegInit({val i = Wire(Valid(in.bits.cloneType)); i.valid := false.B; i.bits := DontCare ; i})
    val out = V2D(reg)
    when (out.ready || !reg.valid) {
      reg.valid := in.valid
      reg.bits := in.bits
    }
    in.ready := out.ready || !reg.valid
    out
  }
}

class ProgrammableSubAddr(maskBits: Int) extends Bundle {
  val offset = UInt(32.W) // TODO:fixme
  val mask = UInt(maskBits.W) // Must be contiguous high bits starting from LSB
  def getSubAddr(fullAddr: UInt): UInt = (fullAddr >> offset) & mask

  // Used to produce a one-hot vector, indicating positions that can be addressed
  def maskToOH(): UInt = {
    val decodings = Seq.tabulate(maskBits)({ i => ((1 << (1 << (i + 1))) - 1).U})
    MuxCase(1.U, (mask.toBools.zip(decodings)).reverse)
  }

  override def cloneType = new ProgrammableSubAddr(maskBits).asInstanceOf[this.type]

}

class DynamicLatencyPipeIO[T <: Data](gen: T, entries: Int, countBits: Int)
    extends QueueIO(gen, entries) {
  val latency = Input(UInt(countBits.W))
  val tCycle = Input(UInt(countBits.W))

  override def cloneType = new DynamicLatencyPipeIO(gen, entries, countBits).asInstanceOf[this.type]
}

// I had to copy this code because critical fields are now private
class DynamicLatencyPipe[T <: Data](
    gen: T,
    entries: Int,
    countBits: Int,
    pipe: Boolean = false,
    flow: Boolean = false
  ) extends Module {
  require(!flow, "Flow not yet supported in DynamicLatencyPipe")
  val io = IO(new DynamicLatencyPipeIO(gen, entries, countBits))

  val ram = Mem(entries, gen)
  val enq_ptr = Counter(entries)
  val deq_ptr = Counter(entries)
  val maybe_full = Reg(init=false.B)

  val ptr_match = enq_ptr.value === deq_ptr.value
  val empty = ptr_match && !maybe_full
  val full = ptr_match && maybe_full
  val do_enq = Wire(init=io.enq.fire())
  val do_deq = Wire(init=io.deq.fire())

  when (do_enq) {
    ram(enq_ptr.value) := io.enq.bits
    enq_ptr.inc()
  }
  when (do_deq) {
    deq_ptr.inc()
  }
  when (do_enq != do_deq) {
    maybe_full := do_enq
  }

  io.enq.ready := !full
  io.deq.bits := ram(deq_ptr.value)

  if (flow) {
    when (io.enq.valid) { io.deq.valid := true.B }
    when (empty) {
      io.deq.bits := io.enq.bits
      do_deq := false.B
      when (io.deq.ready) { do_enq := false.B }
    }
  }

  if (pipe) {
    when (io.deq.ready) { io.enq.ready := true.B }
  }

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

// [WIP] This generates a collapsing buffer and returns a set of wires for updating
// elements in the buffer without needing to worry about them shifting
object CollapsingBuffer {
  def apply[T <: Data](entries: Seq[ValidIO[T]], enq: DecoupledIO[T]): Seq[Valid[T]] = {
    def genStage[T <: Data](entries: Seq[Tuple2[ValidIO[T], ValidIO[T]]], shifting: Bool): Unit = entries match {
      case Nil => throw new RuntimeException("Not possible")
      // Final entry, connect up the enqueuer
      case (entry, currentUpdate) :: Nil => {
        val shift = shifting || !currentUpdate.valid
        entry := Mux(shift, D2V(enq), currentUpdate)
        enq.ready := shift
      }
      // Default case
      case (entry, currentUpdate) :: tail => {
        val youngerUpdate = tail.head._2
        val shift = shifting || !currentUpdate.valid
        entry := Mux(shift, youngerUpdate, currentUpdate)
        genStage(tail, shift)
      }
    }

    val updateEntries = entries.map({ reg => Wire(init = reg) })
    genStage(entries.zip(updateEntries), false.B)
    updateEntries
  }
}

trait HasTransactionMetaData {
  def idBits: Int
  def lenBits: Int
}

case class MetaDataWidths(idBits: Int, lenBits: Int) extends HasTransactionMetaData

object AXI4MetaDataWidths { // Hack
  def apply()(implicit p: Parameters): MetaDataWidths = MetaDataWidths(p(NastiKey).idBits, 8)
}

class TransactionMetaData(val key: MetaDataWidths) extends GenericParameterizedBundle(key) {
  val id = UInt(key.idBits.W)
  val len = UInt(key.lenBits.W)
  val isWrite = Bool()
}

class ReadMetaData(val key: MetaDataWidths) extends GenericParameterizedBundle(key) {
  val id = UInt(key.idBits.W)
  val len = UInt(key.lenBits.W)
}

object ReadMetaData {
  def apply(md: TransactionMetaData): ReadMetaData = {
    val readMetaData = Wire(new ReadMetaData(md.key))
    readMetaData.id := md.id
    readMetaData.len := md.len
    readMetaData
  }
}

class WriteMetaData(key: MetaDataWidths) extends GenericParameterizedBundle(key) {
  val id = UInt(key.idBits.W)
}

object WriteMetaData {
  def apply(md: TransactionMetaData): WriteMetaData = {
    val writeMetaData = Wire(new WriteMetaData(md.key))
    writeMetaData.id := md.id
    writeMetaData
  }
}

trait AXI4BackendIO extends Bundle {
  implicit val p: Parameters
  val b = Decoupled(new NastiWriteResponseChannel)
  val r = Decoupled(new NastiReadDataChannel)
  val egressReq = new EgressReq
  val egressResp = Flipped(new EgressResp)
}

// Accepts transactions from MAS
class UnifiedAXI4BackendIO(key: MetaDataWidths)(implicit val p: Parameters)
    extends GenericParameterizedBundle(key) with AXI4BackendIO {
  val newXaction = Flipped(Decoupled(new TransactionMetaData(key)))
}

// Can accept a read and write resp in a single cycle. Useful for simple models
// that divide read and write pipes
class SplitAXI4BackendIO(key: MetaDataWidths)(implicit val p: Parameters)
    extends GenericParameterizedBundle(key) with AXI4BackendIO {
  val newRead = Flipped(Decoupled(new ReadMetaData(key)))
  val newWrite = Flipped(Decoupled(new WriteMetaData(key)))
}


abstract class AXI4Backend(cfg: BaseConfig)(implicit p: Parameters) extends Module {
  val io: AXI4BackendIO

  val rQueue = Module(new Queue(new ReadMetaData(AXI4MetaDataWidths()), cfg.maxReads))
  val wQueue = Module(new Queue(new WriteMetaData(AXI4MetaDataWidths()), cfg.maxWrites))

  val currentRead = DecoupledPipeStage(rQueue.io.deq) //FIXME
  currentRead.ready := io.r.fire && io.r.bits.last
  io.egressReq.r.valid := rQueue.io.deq.fire
  io.egressReq.r.bits := rQueue.io.deq.bits.id
  io.r.valid := currentRead.valid
  io.r.bits := io.egressResp.rBits
  io.egressResp.rReady := io.r.ready

  val currentWrite = DecoupledPipeStage(wQueue.io.deq)

  currentWrite.ready := io.b.fire
  io.egressReq.b.valid := wQueue.io.deq.fire
  io.egressReq.b.bits := wQueue.io.deq.bits.id
  io.b.valid := currentWrite.valid
  io.b.bits := io.egressResp.bBits
  io.egressResp.bReady := io.b.ready
}

class UnifiedAXI4Backend(cfg: BaseConfig)(implicit p: Parameters) extends AXI4Backend(cfg)(p) {
  lazy val io = IO(new UnifiedAXI4BackendIO(AXI4MetaDataWidths()))

  rQueue.io.enq.bits := ReadMetaData(io.newXaction.bits)
  rQueue.io.enq.valid := io.newXaction.valid && !io.newXaction.bits.isWrite

  wQueue.io.enq.bits := WriteMetaData(io.newXaction.bits)
  wQueue.io.enq.valid := io.newXaction.valid && io.newXaction.bits.isWrite

  io.newXaction.ready := Mux(io.newXaction.bits.isWrite, wQueue.io.enq.ready, rQueue.io.enq.ready)
}

// Used in simple timing models where a read and write may be retired in a single cycle
class SplitAXI4Backend(cfg: BaseConfig)(implicit p: Parameters)
    extends AXI4Backend(cfg)(p) {
  lazy val io = IO(new SplitAXI4BackendIO(AXI4MetaDataWidths()))

  rQueue.io.enq <> io.newRead
  wQueue.io.enq <> io.newWrite
}
