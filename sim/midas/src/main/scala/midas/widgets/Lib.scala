// See LICENSE for license details.

package midas
package widgets

import core.HostDecoupledIO
import junctions._

import chisel3._
import chisel3.util._
import chisel3.core.ActualDirection
import chisel3.core.DataMirror.directionOf
import freechips.rocketchip.config.Parameters
import scala.collection.mutable.{ArrayBuffer, LinkedHashMap}

import CppGenerationUtils._
/** Takes an arbtirary Data type, and flattens it (akin to .flatten()).
  * Returns a Seq of the leaf nodes with their absolute direction.
  */
object FlattenData {
  def apply[T <: Data](gen: T): Seq[(Data, ActualDirection)] = {
    gen match {
      case a : Aggregate => a.getElements flatMap(e => this(e))
      case e : Element => Seq((e, directionOf(e)))
      case _ => throw new RuntimeException("Cannot handle this type")
    }
  }
}

/** An object that is useful for measuring the QoR of a module on FPGA
  * CAD tools; achieves two goals
  * 1) Registers all inputs/outputs to properly measure intra-module timing
  * 2) Inserts a scan chain across the elements - this reduces the total module
  *    I/O, and prevents the FPGA CAD tools from optimizing I/O driven paths
  */
object ScanRegister {
  def apply(data: Seq[Data], scanEnable: Bool, scanIn: Bool): Bool = {
    val leaves = data flatMap FlattenData.apply
    leaves.foldLeft(scanIn)((in: Bool, leaf: (Data, ActualDirection)) => {
      val r = Reg(VecInit(leaf._1.asUInt.toBools).cloneType)
      (leaf._2) match {
        case ActualDirection.Output =>
          r := VecInit(leaf._1.asUInt.toBools)
        case ActualDirection.Input =>
          leaf._1 := r.asUInt
        case _ => throw new RuntimeException("Directions on all elements must be specified")
      }

      val out = WireInit(false.B)
      when (scanEnable) {
        out := r.foldLeft(in)((in: Bool, r: Bool) => {r := in; r })
      }
      out
    })
  }
}

class SatUpDownCounterIO(val n: Int) extends Bundle {
  val inc = Input(Bool())
  val dec = Input(Bool())
  val set = Input(Valid(UInt(log2Up(n+1).W)))
  val max = Input(UInt(log2Up(n+1).W))
  val value = Output(UInt())
  val full = Output(Bool())
  val empty = Output(Bool())
}
/** A saturating up down counter.
  *
  *  @param n The maximum value at which the counter will saturate.
  */
class SatUpDownCounter(val n: Int) extends Module {
  require(n >= 1)
  val io = IO(new SatUpDownCounterIO(n))
  val value =  RegInit(0.U(log2Up(n + 1).W))
  io.value := value
  io.full := value >= io.max
  io.empty := value === 0.U

  when (io.set.valid) {
    io.value := io.set.bits
  }.elsewhen (io.inc && ~io.dec && ~io.full) {
    value := value + 1.U
  }.elsewhen(~io.inc && io.dec && ~io.empty){
    value := value - 1.U
  }
}

object SatUpDownCounter {
  def apply(n: Int): SatUpDownCounterIO = {
    val c = (Module(new SatUpDownCounter(n))).io
    c.max := n.U
    c.inc := false.B
    c.set.valid := false.B
    c.dec := false.B
    c.set.bits := DontCare
    c
  }
}

class MultiQueueIO[T <: Data](private val gen: T, val numQueues: Int, entries: Int) extends
    QueueIO(gen, entries) {
  val enqAddr = Input(UInt(log2Up(numQueues).W))
  val deqAddr = Input(UInt(log2Up(numQueues).W))
  val empty = Output(Bool())
}
/** An extension of queue that co locates a set of Queues at a single mem.
  * Key assumptions:
  * 1) A writer to a queue dumps a complete transaction into a single queue
  *    before it proceeds to enq to another queue.
  * 2) A reader consumes the contents of a queue entirely before reading from another
  *    This way we require only a single set of read and write pointers
  */
class MultiQueue[T <: Data](
    gen: T,
    val numQueues: Int,
    requestedEntries: Int
    ) extends Module {

  val entries = 1 << log2Ceil(requestedEntries)
  val io = IO(new MultiQueueIO(gen, numQueues, entries))
  io.count := DontCare
  // Rely on the ROB & freelist to ensure we are always enq-ing to an available
  // slot

  val ram = SyncReadMem(entries * numQueues, gen)
  val enqPtrs = RegInit(VecInit(Seq.fill(numQueues)(0.U(log2Up(entries).W))))
  val deqPtrs = RegInit(VecInit(Seq.fill(numQueues)(0.U(log2Up(entries).W))))
  val maybe_full = RegInit(VecInit(Seq.fill(numQueues)(false.B)))
  val ptr_matches = VecInit.tabulate(numQueues)(i => enqPtrs(i) === deqPtrs(i))

  val empty = Wire(Bool())
  val full = ptr_matches(io.enqAddr) && maybe_full(io.enqAddr)
  val do_enq = WireInit(io.enq.fire)
  val do_deq = WireInit(io.deq.fire)
  val deqAddrReg = RegNext(io.deqAddr)

  when (do_enq) {
    ram(Cat(io.enqAddr, enqPtrs(io.enqAddr))) := io.enq.bits
    enqPtrs(io.enqAddr) := enqPtrs(io.enqAddr) + 1.U
  }
  when (do_deq) {
    deqPtrs(deqAddrReg) := deqPtrs(deqAddrReg) + 1.U
  }
  when (io.enqAddr === deqAddrReg) {
    when(do_enq =/= do_deq) {
    maybe_full(io.enqAddr) := do_enq
    }
  }.otherwise {
    when(do_enq) {
      maybe_full(io.enqAddr) := true.B
    }
    when (do_deq) {
      maybe_full(deqAddrReg) := false.B
    }
  }

  val deqPtr = Wire(UInt())
  when(do_deq && (deqAddrReg === io.deqAddr)) {
    deqPtr := deqPtrs(io.deqAddr) + 1.U
    empty := (deqPtrs(io.deqAddr) + 1.U) === enqPtrs(io.deqAddr)
  }.otherwise {
    deqPtr := deqPtrs(io.deqAddr)
    empty := ptr_matches(io.deqAddr) && !maybe_full(io.deqAddr)
  }
  val deqValid = RegNext(!empty, false.B)
  io.empty := empty
  io.deq.valid := deqValid
  io.enq.ready := !full
  io.deq.bits := ram.read(Cat(io.deqAddr, deqPtr))
}

// Selects one of two input host decoupled channels. Drives ready false
// to the unselected channel.
object HostMux {
  def apply[T <: Data](sel: Bool, a : HostDecoupledIO[T], b : HostDecoupledIO[T]) : HostDecoupledIO[T] =
  {
    val output = Wire(a.cloneType)
    output.hValid := a.hValid || b.hValid
    output.hBits := Mux(sel, a.hBits, b.hBits)
    a.hReady := sel && output.hReady
    b.hReady := ~sel && output.hReady
    output
  }
}

case class Permissions(readable: Boolean, writeable: Boolean)
object ReadOnly extends Permissions(true, false)
object WriteOnly extends Permissions(false, true)
object ReadWrite extends Permissions(true, true)

abstract class MCRMapEntry {
  def name: String
  def permissions: Permissions
}


case class DecoupledSinkEntry(node: DecoupledIO[UInt], name: String) extends MCRMapEntry {
  val permissions = WriteOnly
}
case class DecoupledSourceEntry(node: DecoupledIO[UInt], name: String) extends MCRMapEntry {
  val permissions = ReadOnly
}
case class RegisterEntry(node: Data, name: String, permissions: Permissions) extends MCRMapEntry


class MCRFileMap() {
  private val name2addr = LinkedHashMap[String, Int]()
  private val regList = ArrayBuffer[MCRMapEntry]()

  def allocate(entry: MCRMapEntry): Int = {
    Predef.assert(!name2addr.contains(entry.name), "name already allocated")
    val address = name2addr.size
    name2addr += (entry.name -> address)
    regList.append(entry)
    address
  }

  def lookupAddress(name: String): Option[Int] = name2addr.get(name)

  def numRegs: Int = regList.size

  def bindRegs(mcrIO: MCRIO): Unit = regList.zipWithIndex foreach {
    case (e: DecoupledSinkEntry, addr) => mcrIO.bindDecoupledSink(e, addr)
    case (e: DecoupledSourceEntry, addr) => mcrIO.bindDecoupledSource(e, addr)
    case (e: RegisterEntry, addr) => mcrIO.bindReg(e, addr)
  }

  def genHeader(prefix: String, base: BigInt, sb: StringBuilder): Unit = {
    // get widget name with no widget number (prefix includes it)
    val prefix_no_num = prefix.split("_")(0)

    // emit generic struct for this widget type. guarded so it only gets
    // defined once
    sb append s"#ifndef ${prefix_no_num}_struct_guard\n"
    sb append s"#define ${prefix_no_num}_struct_guard\n"
    sb append s"typedef struct ${prefix_no_num}_struct {\n"
    name2addr.toList foreach { case (regName, idx) =>
      sb append s"    unsigned long ${regName};\n"
    }
    sb append s"} ${prefix_no_num}_struct;\n"
    sb append s"#endif // ${prefix_no_num}_struct_guard\n\n"

    // define to mark that a particular instantiation of a widget is present
    // (e.g. UARTWIDGET_0_PRESENT)
    sb append s"#define ${prefix}_PRESENT\n"

    // emit macro to create a version of this struct with values filled in
    sb append s"#define ${prefix}_substruct_create \\\n"
    // assume the widget destructor will free this
    sb append s"${prefix_no_num}_struct * ${prefix}_substruct = (${prefix_no_num}_struct *) malloc(sizeof(${prefix_no_num}_struct)); \\\n"
    name2addr.toList foreach { case (regName, idx) =>
      val address = base + idx
      sb append s"${prefix}_substruct->${regName} = ${address}; \\\n"
    }
    sb append s"\n"
  }

  // A variation of above which dumps the register map as a series of arrays
  def genArrayHeader(prefix: String, base: BigInt, sb: StringBuilder) {
    def emitArrays(regs: Seq[(MCRMapEntry, BigInt)], prefix: String) {
      sb.append(genConstStatic(s"${prefix}_num_registers", UInt32(regs.size)))
      sb.append(genArray(s"${prefix}_names", regs.unzip._1 map { reg => CStrLit(reg.name)}))
      sb.append(genArray(s"${prefix}_addrs", regs.unzip._2 map { addr => UInt32(addr)}))
    }

    val regAddrs = regList map (reg => reg -> (base + lookupAddress(reg.name).get))
    val readRegs = regAddrs filter (_._1.permissions.readable)
    val writeRegs = regAddrs filter (_._1.permissions.writeable)
    emitArrays(readRegs, prefix + "_R")
    emitArrays(writeRegs, prefix + "_W")
  }

  // Returns a copy of the current register map
  def getRegMap = name2addr.toMap

  def printCRs {
    regList.zipWithIndex foreach { case (entry, i) => println(s"Name: ${entry.name}, Addr: $i") }
  }
}

class MCRIO(numCRs: Int)(implicit p: Parameters) extends NastiBundle()(p) {
  val read = Vec(numCRs, Flipped(Decoupled(UInt(nastiXDataBits.W))))
  val write = Vec(numCRs, Decoupled(UInt(nastiXDataBits.W)))
  val wstrb = Output(UInt(nastiWStrobeBits.W))

  def bindReg(reg: RegisterEntry, addr: Int): Unit = {
    if (reg.permissions.writeable) {
      when(write(addr).valid){
        reg.node := write(addr).bits
      }
    } else {
      assert(write(addr).valid =/= true.B, s"Register ${reg.name} is read only")
    }

    if (reg.permissions.readable) {
      read(addr).bits := reg.node
    } else {
      assert(read(addr).ready === false.B, "Register ${reg.name} is write only")
    }

    read(addr).valid := true.B
    write(addr).ready := true.B
  }

  def bindDecoupledSink(channel: DecoupledSinkEntry, addr: Int): Unit = {
    channel.node <> write(addr)
    assert(read(addr).ready === false.B, "Can only write to this decoupled sink")
  }

  def bindDecoupledSource(channel: DecoupledSourceEntry, addr: Int): Unit = {
    read(addr) <> channel.node
    assert(write(addr).valid =/= true.B, "Can only read from this decoupled source")
  }

}

class MCRFile(numRegs: Int)(implicit p: Parameters) extends NastiModule()(p) {
  val io = IO(new Bundle {
    val nasti = Flipped(new NastiIO)
    val mcr = new MCRIO(numRegs)
  })

  //TODO: Just use a damn state machine.
  val rValid = RegInit(false.B)
  val arFired = RegInit(false.B)
  val awFired = RegInit(false.B)
  val wFired = RegInit(false.B)
  val wCommited = RegInit(false.B)
  val bId = Reg(UInt(p(NastiKey).idBits.W))
  val rId = Reg(UInt(p(NastiKey).idBits.W))
  val rData = Reg(UInt(nastiXDataBits.W))
  val wData = Reg(UInt(nastiXDataBits.W))
  val wAddr = Reg(UInt(log2Up(numRegs).W))
  val rAddr = Reg(UInt(log2Up(numRegs).W))
  val wStrb = Reg(UInt(nastiWStrobeBits.W))

  when(io.nasti.aw.fire()){
    awFired := true.B
    wAddr := io.nasti.aw.bits.addr >> log2Up(nastiWStrobeBits)
    bId := io.nasti.aw.bits.id
    assert(io.nasti.aw.bits.len === 0.U)
  }

  when(io.nasti.w.fire()){
    wFired := true.B
    wData := io.nasti.w.bits.data
    wStrb := io.nasti.w.bits.strb
  }

  when(io.nasti.ar.fire()) {
    arFired := true.B
    rAddr := (io.nasti.ar.bits.addr >> log2Up(nastiWStrobeBits))(log2Up(numRegs)-1,0)
    rId := io.nasti.ar.bits.id
    assert(io.nasti.ar.bits.len === 0.U, "MCRFile only support single beat reads")
  }

  when(io.nasti.r.fire()) {
    arFired := false.B
  }

  when(io.nasti.b.fire()) {
    awFired := false.B
    wFired := false.B
    wCommited := false.B
  }

  when(io.mcr.write(wAddr).fire()){
    wCommited := true.B
  }

  io.mcr.write foreach { w => w.valid := false.B; w.bits := wData }
  io.mcr.write(wAddr).valid := awFired && wFired && ~wCommited
  io.mcr.read.zipWithIndex foreach { case (decoupled, idx: Int) =>
    decoupled.ready := (rAddr === idx.U) && arFired && io.nasti.r.ready
  }

  io.nasti.r.bits := NastiReadDataChannel(rId, io.mcr.read(rAddr).bits)
  io.nasti.r.valid := arFired && io.mcr.read(rAddr).valid

  io.nasti.b.bits := NastiWriteResponseChannel(bId)
  io.nasti.b.valid := awFired && wFired && wCommited

  io.nasti.ar.ready := ~arFired
  io.nasti.aw.ready := ~awFired
  io.nasti.w.ready := ~wFired
}

class CRIO(direction: ActualDirection, width: Int, val default: Int) extends Bundle {
  val value = (direction: @unchecked) match {
    case ActualDirection.Input => Input(UInt(width.W))
    case ActualDirection.Output => Output(UInt(width.W))
  }
  def apply(dummy: Int = 0) = value
}

object CRIO {
  def apply(direction: ActualDirection, width: Int, default: Int) =
    new CRIO(direction, width, default)
}

class DecoupledCRIO[+T <: Data](gen: T) extends DecoupledIO[T](gen) {
  override def cloneType: this.type = new DecoupledIO(gen).asInstanceOf[this.type]
}
object DecoupledCRIO {
  def apply[T <: Data](gen: T): DecoupledCRIO[T] = new DecoupledCRIO(gen)
}

// I need the right name for this
object D2V {
  def apply[T <: Data](in: DecoupledIO[T]): ValidIO[T] = {
    val v = Wire(Valid(in.bits.cloneType))
    v.bits := in.bits
    v.valid := in.valid
    v
  }
}
object V2D {
  def apply[T <: Data](in: ValidIO[T]): DecoupledIO[T] = {
    val d = Wire(Decoupled(in.bits.cloneType))
    d.bits := in.bits
    d.valid := in.valid
    d
  }
}
// Holds a ValidIO in a register until it is no longer needed.
// Not quite indentical to a Decoupled Skid register but similar
object HoldingRegister {
  def apply[T <: Data](in: ValidIO[T], done: Bool): (ValidIO[T], ValidIO[T]) = {
    val reg = RegInit({val i = Wire(in.cloneType); i.valid := false.B; i})
    val out = Mux(~reg.valid || done, in, reg)
    reg := out
    (out, reg)
  }
}

object SkidRegister {
  def apply[T <: Data](in: DecoupledIO[T]): DecoupledIO[T] = {
    val reg = RegInit({val i = Wire(Valid(in.bits.cloneType)); i.valid := false.B; i})
    val out = Wire(in.cloneType)
    in.ready := ~reg.valid || out.ready
    out.valid := reg.valid || in.valid
    out.bits := Mux(reg.valid, reg.bits, in.bits)
    when (out.valid && ~out.ready) {
      reg.bits := out.bits
      reg.valid := true.B
    }
    out
  }
}

class IdentityModule[T <: Data](gen: T) extends Module
{
  val io = IO(new Bundle {
    val in = Flipped(gen.cloneType)
    val out = gen.cloneType
  })

  io.out <> io.in
}

object IdentityModule
{
  def apply[T <: Data](x: T): T = {
    val identity = Module(new IdentityModule(x))
    identity.io.in := x
    identity.io.out
  }
}

// Generates a queue that will be implemented in BRAM
class BRAMFlowQueue[T <: Data](val entries: Int)(data: => T) extends Module {
  val io = IO(new QueueIO(data, entries))
  require(entries > 1)

  io.count := 0.U

  val do_flow = Wire(Bool())
  val do_enq = io.enq.fire() && !do_flow
  val do_deq = io.deq.fire() && !do_flow

  val maybe_full = RegInit(false.B)
  val enq_ptr = Counter(do_enq, entries)._1
  val (deq_ptr, deq_done) = Counter(do_deq, entries)
  when (do_enq =/= do_deq) { maybe_full := do_enq }

  val ptr_match = enq_ptr === deq_ptr
  val empty = ptr_match && !maybe_full
  val full = ptr_match && maybe_full
  val atLeastTwo = full || enq_ptr - deq_ptr >= 2.U
  do_flow := empty && io.deq.ready

  val ram = Mem(entries, data)
  when (do_enq) { ram.write(enq_ptr, io.enq.bits) }

  val ren = io.deq.ready && (atLeastTwo || !io.deq.valid && !empty)
  val raddr = Mux(io.deq.valid, Mux(deq_done, 0.U, deq_ptr + 1.U), deq_ptr)
  val ram_out_valid = RegNext(ren)

  io.deq.valid := Mux(empty, io.enq.valid, ram_out_valid)
  io.enq.ready := !full
  io.deq.bits := Mux(empty, io.enq.bits, RegEnable(ram.read(raddr), ren))
}

class BRAMQueue[T <: Data](val entries: Int)(data: => T) extends Module {
  val io = IO(new QueueIO(data, entries))

  io.count := 0.U

  val fq = Module(new BRAMFlowQueue(entries)(data))
  fq.io.enq <> io.enq
  io.deq <> Queue(fq.io.deq, 1, pipe = true)
}

object BRAMQueue {
  def apply[T <: Data](enq: DecoupledIO[T], entries: Int) = {
    val q = Module((new BRAMQueue(entries)) { enq.bits.cloneType })
    q.io.enq.valid := enq.valid // not using <> so that override is allowed
    q.io.enq.bits := enq.bits
    enq.ready := q.io.enq.ready
    q.io.deq
  }
}

