package midas_widgets

import chisel3._
import chisel3.util._
import junctions._
import cde.{Parameters, Field}
import scala.collection.mutable.HashMap
import scala.collection.mutable.ArrayBuffer

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
  def apply[T <: Data](data : T, scanEnable: Bool, scanIn: Bool): Bool = {
    val leaves = FlattenData(data)
    leaves.foldLeft(scanIn)((in: Bool, leaf: Tuple2[Data,Direction]) => {
      val r = Reg(Vec(leaf._1.toBits.toBools))
      if(leaf._2 == OUTPUT){
        r := leaf._1.toBits.toBools
      } else {
        leaf._1 := leaf._1.fromBits(r.reduce[UInt](_ ## _))
      }

      val out = Wire(Bool(false))
      when (scanEnable) {
        out := r.foldLeft(in)((in: Bool, r: Bool) => {r := in; r })
      }
      out
    })
  }
}

class SatUpDownCounterIO(val n: Int) extends Bundle {
  val inc = Bool(INPUT)
  val dec = Bool(INPUT)
  val max = UInt(INPUT, width = (log2Up(n)))
  val value = UInt(OUTPUT)
  val full = Bool(OUTPUT)
  val empty = Bool(OUTPUT)
}
/** A saturating up down counter
  */
class SatUpDownCounter(val n: Int) extends Module {
  require(n >= 1)
  val io = IO(new SatUpDownCounterIO(n))
  val value =  Reg(init=UInt(0, log2Up(n)))
  io.value := value
  io.full := value >= io.max
  io.empty := value === UInt(0)

  when (io.inc && ~io.dec && ~io.full) {
    value := value + UInt(1)
  }.elsewhen(~io.inc && io.dec && ~io.empty){
    value := value - UInt(1)
  }
}

object SatUpDownCounter {
  def apply(n: Int): SatUpDownCounterIO = {
    val c = (Module(new SatUpDownCounter(n))).io
    c.max := UInt(n)
    c.inc := Bool(false)
    c.dec := Bool(false)
    c
  }
}

class MultiQueueIO[T <: Data](gen: T, numQueues: Int, entries: Int) extends
    QueueIO(gen, entries) {
  val enqAddr = UInt(INPUT, width = log2Up(numQueues))
  val deqAddr = UInt(INPUT, width = log2Up(numQueues))
  val empty = Bool(OUTPUT)
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
  val enqPtrs = RegInit(Vec.fill(numQueues)(UInt(0, width = log2Up(entries))))
  val deqPtrs = RegInit(Vec.fill(numQueues)(UInt(0, width = log2Up(entries))))
  val maybe_full = RegInit(Vec.fill(numQueues)(Bool(false)))
  val ptr_matches = Vec.tabulate(numQueues)(i => enqPtrs(i) === deqPtrs(i))

  val empty = Wire(Bool())
  val full = ptr_matches(io.enqAddr) && maybe_full(io.enqAddr)
  val do_enq = Wire(init=io.enq.fire())
  val do_deq = Wire(init=io.deq.fire())
  val deqAddrReg = RegNext(io.deqAddr)

  when (do_enq) {
    ram(Cat(io.enqAddr, enqPtrs(io.enqAddr))) := io.enq.bits
    enqPtrs(io.enqAddr) := enqPtrs(io.enqAddr) + UInt(1)
  }
  when (do_deq) {
    deqPtrs(deqAddrReg) := deqPtrs(deqAddrReg) + UInt(1)
  }
  when (io.enqAddr === deqAddrReg) {
    when(do_enq != do_deq) {
    maybe_full(io.enqAddr) := do_enq
    }
  }.otherwise {
    when(do_enq) {
      maybe_full(io.enqAddr) := Bool(true)
    }
    when (do_deq) {
      maybe_full(deqAddrReg) := Bool(false)
    }
  }

  val deqPtr = Wire(UInt())
  when(do_deq && (deqAddrReg === io.deqAddr)) {
    deqPtr := deqPtrs(io.deqAddr) + UInt(1)
    empty := (deqPtrs(io.deqAddr) + UInt(1)) === enqPtrs(io.enqAddr)
  }.otherwise {
    deqPtr := deqPtrs(io.deqAddr)
    empty := ptr_matches(io.deqAddr) && !maybe_full(io.deqAddr)
  }
  val deqValid = RegNext(!empty, Bool(false))
  io.empty := empty
  io.deq.valid := deqValid
  io.enq.ready := !full
  io.deq.bits := ram.read(Cat(io.deqAddr, deqPtr), Bool(true))
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

abstract class MCRMapEntry {
  def name: String
}
case class DecoupledSinkEntry(node: DecoupledIO[UInt], name: String) extends MCRMapEntry
case class DecoupledSourceEntry(node: DecoupledIO[UInt], name: String) extends MCRMapEntry
case class RegisterEntry(node: Bits, name: String) extends MCRMapEntry


class MCRFileMap() {
  private val name2addr = HashMap.empty[String, Int]
  private val regList = ArrayBuffer.empty[MCRMapEntry]

  def allocate(entry: MCRMapEntry): Int = {
    Predef.assert(!name2addr.contains(entry.name), "name already allocated")
    val address = name2addr.size
    name2addr += (entry.name -> address)
    regList.append(entry)
    address
  }

  def lookupAddress(name: String): Option[Int] = name2addr.get(name)

  def numRegs(): Int = regList.size

  def bindRegs(mcrIO: MCRIO): Unit = {
    (regList.toSeq.zipWithIndex) foreach { case(entry, addr) =>
      entry match {
        case (e: DecoupledSinkEntry) => mcrIO.bindDecoupledSink(e, addr)
        case (e: DecoupledSourceEntry) => mcrIO.bindDecoupledSource(e, addr)
        case (e: RegisterEntry) => mcrIO.bindReg(e, addr)
      }
    }
  }

  def genHeader(prefix: String, base: BigInt, sb: StringBuilder): Unit = {
    name2addr foreach {
      case(regName: String, idx: Int) => {
        val fullName = s"${prefix}_${regName}"
        val address = base + idx
        sb append s"#define ${fullName} ${address}\n"
      }
    }
  }

  // Returns a copy of the current register map
  def getRegMap = name2addr.toMap

  def printCRs(): Unit = {
    regList.zipWithIndex foreach { case (entry, i) => println(s"Name: ${entry.name}, Addr: $i") }
  }
}

class MCRIO(numCRs: Int)(implicit p: Parameters) extends NastiBundle()(p) {
  val read = Vec(numCRs, Flipped(Decoupled(UInt(width = nastiXDataBits))))
  val write = Vec(numCRs, Decoupled(UInt(width = nastiXDataBits)))
  val wstrb = Bits(OUTPUT, nastiWStrobeBits)

  def bindReg(reg: RegisterEntry, addr: Int): Unit = {
    when(write(addr).valid){
      reg.node := write(addr).bits
    }
    write(addr).ready := Bool(true)
    read(addr).bits := reg.node
    read(addr).valid := Bool(true)
  }

  def bindDecoupledSink(channel: DecoupledSinkEntry, addr: Int): Unit = {
    channel.node <> write(addr)
    assert(read(addr).ready === Bool(false), "Can only write to this decoupled sink")
  }

  def bindDecoupledSource(channel: DecoupledSourceEntry, addr: Int): Unit = {
    read(addr) <> channel.node
    assert(write(addr).valid =/= Bool(true), "Can only read from this decoupled source")
  }

}

class MCRFile(numRegs: Int)(implicit p: Parameters) extends NastiModule()(p) {
  val io = IO(new Bundle {
    val nasti = Flipped(new NastiIO)
    val mcr = new MCRIO(numRegs)
  })

  //TODO: Just use a damn state machine.
  val rValid = RegInit(Bool(false))
  val arFired = RegInit(Bool(false))
  val awFired = RegInit(Bool(false))
  val wFired = RegInit(Bool(false))
  val wCommited = RegInit(Bool(false))
  val bId = Reg(UInt(width = p(NastiKey).idBits))
  val rId = Reg(UInt(width = p(NastiKey).idBits))
  val rData = Reg(UInt(width = nastiXDataBits))
  val wData = Reg(UInt(width = nastiXDataBits))
  val wAddr = Reg(UInt(width = log2Up(numRegs)))
  val rAddr = Reg(UInt(width = log2Up(numRegs)))
  val wStrb = Reg(UInt(width = nastiWStrobeBits))

  when(io.nasti.aw.fire()){
    awFired := Bool(true)
    wAddr := io.nasti.aw.bits.addr >> log2Up(nastiWStrobeBits)
    bId := io.nasti.aw.bits.id
    assert(io.nasti.aw.bits.len === UInt(0))
  }

  when(io.nasti.w.fire()){
    wFired := Bool(true)
    wData := io.nasti.w.bits.data
    wStrb := io.nasti.w.bits.strb
  }

  when(io.nasti.ar.fire()) {
    arFired := Bool(true)
    rAddr := (io.nasti.ar.bits.addr >> log2Up(nastiWStrobeBits))(log2Up(numRegs)-1,0)
    rId := io.nasti.ar.bits.id
    assert(io.nasti.ar.bits.len === UInt(0), "MCRFile only support single beat reads")
  }

  when(io.nasti.r.fire()) {
    arFired := Bool(false)
  }

  when(io.nasti.b.fire()) {
    awFired := Bool(false)
    wFired := Bool(false)
    wCommited := Bool(false)
  }

  when(io.mcr.write(wAddr).fire()){
    wCommited := Bool(true)
  }

  io.mcr.write foreach { w => w.valid := Bool(false); w.bits := wData }
  io.mcr.read foreach { _.ready := Bool(false) }
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

class CRIO(direction: Direction, width: Int, val default: Int) extends Bundle {
  val value = UInt(direction, width)
  def apply(dummy: Int = 0) = value
}

object CRIO {
  def apply(direction: Direction, width: Int, default: Int) = new CRIO(direction, width, default)
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
    val reg = RegInit({val i = Wire(in.cloneType); i.valid := Bool(false); i})
    val out = Mux(~reg.valid || done, in, reg)
    reg := out
    (out, reg)
  }
}

object SkidRegister {
  def apply[T <: Data](in: DecoupledIO[T]): DecoupledIO[T] = {
    val reg = RegInit({val i = Wire(Valid(in.bits.cloneType)); i.valid := Bool(false); i})
    val out = Wire(in.cloneType)
    in.ready := ~reg.valid || out.ready
    out.valid := reg.valid || in.valid
    out.bits := Mux(reg.valid, reg.bits, in.bits)
    when (out.valid && ~out.ready) {
      reg.bits := out.bits
      reg.valid := Bool(true)
    }
    out
  }
}


