package midas
package widgets

import core.HostDecoupledIO
// from rokcetchip
import junctions._

import chisel3._
import chisel3.util._
import config.Parameters
import scala.collection.mutable.{ArrayBuffer, LinkedHashMap}

import CppGenerationUtils._
/** Takes an arbtirary Data type, and flattens it (akin to .flatten()).
  * Returns a Seq of the leaf nodes with their absolute/final direction.
  */
object FlattenData {
  // TODO: fix for gsdt
  import Chisel._
  def dirProduct(context: Direction, field: Direction): Direction =
    if (context == INPUT) field.flip else field

  def apply[T <: Data](
      gen: T,
      parentDir: Direction = OUTPUT): Seq[(Data, Direction)] = {
    val currentDir = dirProduct(parentDir, gen.dir)
    gen match {
      case a : Bundle => (a.elements flatMap(e => { this(e._2, currentDir)})).toSeq
      case v : Vec[_] => v.flatMap(el => this(el, currentDir))
      case leaf => Seq((leaf, currentDir))
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
  import Chisel._
  def apply[T <: Data](data : T, scanEnable: Bool, scanIn: Bool): Bool = {
    val leaves = FlattenData(data)
    leaves.foldLeft(scanIn)((in: Bool, leaf: Tuple2[Data,Direction]) => {
      val r = Reg(Vec(leaf._1.toBits.toBools))
      if(leaf._2 == OUTPUT){
        r := leaf._1.toBits.toBools
      } else {
        leaf._1 := leaf._1.fromBits(r.reduce[UInt](_ ## _))
      }

      val out = Wire(false.B)
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
  val value =  Reg(init=UInt(0, log2Up(n + 1)))
  io.value := value
  io.full := value >= io.max
  io.empty := value === 0.U

  when (io.inc && ~io.dec && ~io.full) {
    value := value + 1.U
  }.elsewhen(~io.inc && io.dec && ~io.empty){
    value := value - 1.U
  }
}

object SatUpDownCounter {
  def apply(n: Int): SatUpDownCounterIO = {
    val c = (Module(new SatUpDownCounter(n))).io
    c.max := UInt(n)
    c.inc := false.B
    c.dec := false.B
    c
  }
}

class MultiQueueIO[T <: Data](gen: T, numQueues: Int, entries: Int) extends
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
    val entries: Int
    ) extends Module {

  require(isPow2(entries))
  val io = IO(new MultiQueueIO(gen, numQueues, entries))
  // Rely on the ROB & freelist to ensure we are always enq-ing to an available
  // slot

  val ram = SeqMem(entries * numQueues, gen)
  val enqPtrs = RegInit(Vec.fill(numQueues)(0.U(log2Up(entries).W)))
  val deqPtrs = RegInit(Vec.fill(numQueues)(0.U(log2Up(entries).W)))
  val maybe_full = RegInit(Vec.fill(numQueues)(false.B))
  val ptr_matches = Vec.tabulate(numQueues)(i => enqPtrs(i) === deqPtrs(i))

  val empty = Wire(Bool())
  val full = ptr_matches(io.enqAddr) && maybe_full(io.enqAddr)
  val do_enq = Wire(init=io.enq.fire())
  val do_deq = Wire(init=io.deq.fire())
  val deqAddrReg = RegNext(io.deqAddr)

  when (do_enq) {
    ram(Cat(io.enqAddr, enqPtrs(io.enqAddr))) := io.enq.bits
    enqPtrs(io.enqAddr) := enqPtrs(io.enqAddr) + 1.U
  }
  when (do_deq) {
    deqPtrs(deqAddrReg) := deqPtrs(deqAddrReg) + 1.U
  }
  when (io.enqAddr === deqAddrReg) {
    when(do_enq != do_deq) {
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
    empty := (deqPtrs(io.deqAddr) + 1.U) === enqPtrs(io.enqAddr)
  }.otherwise {
    deqPtr := deqPtrs(io.deqAddr)
    empty := ptr_matches(io.deqAddr) && !maybe_full(io.deqAddr)
  }
  val deqValid = RegNext(!empty, false.B)
  io.empty := empty
  io.deq.valid := deqValid
  io.enq.ready := !full
  io.deq.bits := ram.read(Cat(io.deqAddr, deqPtr), true.B)
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
    name2addr.toList foreach { case (regName, idx) =>
      val fullName = s"${prefix}_${regName}"
      val address = base + idx
      sb append s"#define ${fullName} ${address}\n"
    }
  }
  // A variation of above which dumps the register map as a series of arrays
  def genArrayHeader(prefix: String, base: BigInt, sb: StringBuilder) {
    def emitArrays(regs: Seq[(MCRMapEntry, BigInt)], prefix: String) {
      sb.append(genConstStatic(s"${prefix}_num_registers", UInt32(regs.size)))
      sb.append(genArray(s"${prefix}_names", regs map { reg => CStrLit(reg.name)}))
      sb.append(genArray(s"${prefix}_addrs", regs map { reg => UInt32(base + lookupAddress(reg.name).get)}))
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
      assert(write(addr).valid != true.B, s"Register ${reg.name} is read only")
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
  io.mcr.read foreach { _.ready := false.B }
  io.mcr.write(wAddr).valid := awFired && wFired && ~wCommited
  io.mcr.read(rAddr).ready := arFired && io.nasti.r.ready

  io.nasti.r.bits := NastiReadDataChannel(rId, io.mcr.read(rAddr).bits)
  io.nasti.r.valid := arFired && io.mcr.read(rAddr).valid

  io.nasti.b.bits := NastiWriteResponseChannel(bId)
  io.nasti.b.valid := awFired && wFired && wCommited

  io.nasti.ar.ready := ~arFired
  io.nasti.aw.ready := ~awFired
  io.nasti.w.ready := ~wFired
}

class CRIO(val direction: Direction, width: Int, val default: Int) extends Bundle {
  val value = UInt(Some(direction), width)
  def apply(dummy: Int = 0) = value
}

object CRIO {
  def apply(direction: Direction, width: Int, default: Int) =
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


