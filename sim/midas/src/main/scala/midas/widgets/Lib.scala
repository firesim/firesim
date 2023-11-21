// See LICENSE for license details.

package midas
package widgets

import junctions._

import chisel3._
import chisel3.util._
import chisel3.experimental.DataMirror
import org.chipsalliance.cde.config.Parameters
import scala.collection.mutable.{ArrayBuffer, LinkedHashMap}

/** Takes an arbtirary Data type, and flattens it (akin to .flatten()). Returns a Seq of the leaf nodes with their
  * absolute direction.
  */
object FlattenData  {
  def apply[T <: Data](gen: T): Seq[(Data, ActualDirection)] = {
    gen match {
      case a: Aggregate => a.getElements.flatMap(e => this(e))
      case e: Element   => Seq((e, DataMirror.directionOf(e)))
      case _            => throw new RuntimeException("Cannot handle this type")
    }
  }
}

/** An object that is useful for measuring the QoR of a module on FPGA CAD tools; achieves two goals 1) Registers all
  * inputs/outputs to properly measure intra-module timing 2) Inserts a scan chain across the elements - this reduces
  * the total module I/O, and prevents the FPGA CAD tools from optimizing I/O driven paths
  */
object ScanRegister {
  def apply(data: Seq[Data], scanEnable: Bool, scanIn: Bool): Bool = {
    val leaves = data.flatMap(FlattenData.apply)
    leaves.foldLeft(scanIn)((in: Bool, leaf: (Data, ActualDirection)) => {
      val r = Reg(VecInit(leaf._1.asUInt.asBools).cloneType)
      (leaf._2) match {
        case ActualDirection.Output =>
          r := VecInit(leaf._1.asUInt.asBools)
        case ActualDirection.Input  =>
          leaf._1 := r.asUInt
        case _                      => throw new RuntimeException("Directions on all elements must be specified")
      }

      val out = WireInit(false.B)
      when(scanEnable) {
        out := r.foldLeft(in)((in: Bool, r: Bool) => { r := in; r })
      }
      out
    })
  }
}

class SatUpDownCounterIO(val n: Int) extends Bundle {
  val inc   = Input(Bool())
  val dec   = Input(Bool())
  val set   = Input(Valid(UInt(log2Up(n + 1).W)))
  val max   = Input(UInt(log2Up(n + 1).W))
  val value = Output(UInt())
  val full  = Output(Bool())
  val empty = Output(Bool())
}

/** A saturating up down counter.
  *
  * @param n
  *   The maximum value at which the counter will saturate.
  */
class SatUpDownCounter(val n: Int) extends Module {
  require(n >= 1)
  val io    = IO(new SatUpDownCounterIO(n))
  val value = RegInit(0.U(log2Up(n + 1).W))
  io.value := value
  io.full  := value >= io.max
  io.empty := value === 0.U

  when(io.set.valid) {
    io.value := io.set.bits
  }.elsewhen(io.inc && ~io.dec && ~io.full) {
    value := value + 1.U
  }.elsewhen(~io.inc && io.dec && ~io.empty) {
    value := value - 1.U
  }
}

object SatUpDownCounter {
  def apply(n: Int): SatUpDownCounterIO = {
    val c = (Module(new SatUpDownCounter(n))).io
    c.max       := n.U
    c.inc       := false.B
    c.set.valid := false.B
    c.dec       := false.B
    c.set.bits  := DontCare
    c
  }
}

class MultiQueueIO[T <: Data](private val gen: T, val numQueues: Int, entries: Int) extends QueueIO(gen, entries) {
  val enqAddr = Input(UInt(log2Up(numQueues).W))
  val deqAddr = Input(UInt(log2Up(numQueues).W))
  val empty   = Output(Bool())
}

/** An extension of queue that co locates a set of Queues at a single mem. Key assumptions: 1) A writer to a queue dumps
  * a complete transaction into a single queue before it proceeds to enq to another queue. 2) A reader consumes the
  * contents of a queue entirely before reading from another This way we require only a single set of read and write
  * pointers
  */
class MultiQueue[T <: Data](
  gen:              T,
  val numQueues:    Int,
  requestedEntries: Int,
) extends Module {

  val entries = 1 << log2Ceil(requestedEntries)
  val io      = IO(new MultiQueueIO(gen, numQueues, entries))
  io.count := DontCare
  // Rely on the ROB & freelist to ensure we are always enq-ing to an available
  // slot

  val ram         = SyncReadMem(entries * numQueues, gen)
  val enqPtrs     = RegInit(VecInit(Seq.fill(numQueues)(0.U(log2Up(entries).W))))
  val deqPtrs     = RegInit(VecInit(Seq.fill(numQueues)(0.U(log2Up(entries).W))))
  val maybe_full  = RegInit(VecInit(Seq.fill(numQueues)(false.B)))
  val ptr_matches = VecInit.tabulate(numQueues)(i => enqPtrs(i) === deqPtrs(i))

  val empty      = Wire(Bool())
  val full       = ptr_matches(io.enqAddr) && maybe_full(io.enqAddr)
  val do_enq     = WireInit(io.enq.fire)
  val do_deq     = WireInit(io.deq.fire)
  val deqAddrReg = RegNext(io.deqAddr)

  when(do_enq) {
    ram(Cat(io.enqAddr, enqPtrs(io.enqAddr))) := io.enq.bits
    enqPtrs(io.enqAddr)                       := enqPtrs(io.enqAddr) + 1.U
  }
  when(do_deq) {
    deqPtrs(deqAddrReg) := deqPtrs(deqAddrReg) + 1.U
  }
  when(io.enqAddr === deqAddrReg) {
    when(do_enq =/= do_deq) {
      maybe_full(io.enqAddr) := do_enq
    }
  }.otherwise {
    when(do_enq) {
      maybe_full(io.enqAddr) := true.B
    }
    when(do_deq) {
      maybe_full(deqAddrReg) := false.B
    }
  }

  val deqPtr   = Wire(UInt())
  when(do_deq && (deqAddrReg === io.deqAddr)) {
    deqPtr := deqPtrs(io.deqAddr) + 1.U
    empty  := (deqPtrs(io.deqAddr) + 1.U) === enqPtrs(io.deqAddr)
  }.otherwise {
    deqPtr := deqPtrs(io.deqAddr)
    empty  := ptr_matches(io.deqAddr) && !maybe_full(io.deqAddr)
  }
  val deqValid = RegNext(!empty, false.B)
  io.empty     := empty
  io.deq.valid := deqValid
  io.enq.ready := !full
  io.deq.bits  := ram.read(Cat(io.deqAddr, deqPtr))
}

case class Permissions(readable: Boolean, writeable: Boolean)
object ReadOnly  extends Permissions(true, false)
object WriteOnly extends Permissions(false, true)
object ReadWrite extends Permissions(true, true)

abstract class MCRMapEntry {
  def name:        String
  def permissions: Permissions
  def substruct:   Boolean
}

case class DecoupledSinkEntry(node: DecoupledIO[UInt], name: String, substruct: Boolean) extends MCRMapEntry {
  val permissions = WriteOnly
}
case class DecoupledSourceEntry(node: DecoupledIO[UInt], name: String, substruct: Boolean) extends MCRMapEntry {
  val permissions = ReadOnly
}
case class RegisterEntry(node: Data, name: String, permissions: Permissions, substruct: Boolean) extends MCRMapEntry

/** Manages the metadata associated with a widget's configuration registers (exposed via the control bus). Registers are
  * incrementally allocated, which each register consuming a fixed number of bytes of the address space.
  *
  * This derives from a very early form of CSR handling in Rocket Chip which has since been replaced with diplomacy and
  * its regmapper utilities.
  *
  * @param bytesPerAddress
  *   The number of bytes of address space consumed by each bound register.
  *
  * Historical: MCR -> Midas Configuration Register
  */
class MCRFileMap(bytesPerAddress: Int) {
  private val name2addr = LinkedHashMap[String, Int]()
  private val regList   = ArrayBuffer[MCRMapEntry]()

  def allocate(entry: MCRMapEntry): Int = {
    Predef.assert(!name2addr.contains(entry.name), s"name already allocated '${entry.name}'")
    val address = bytesPerAddress * name2addr.size
    name2addr += (entry.name -> address)
    regList.append(entry)
    address
  }

  def lookupAddress(name: String): Option[Int] = name2addr.get(name)

  def numRegs: Int = regList.size

  /** Return the name-address mapping of registers included in the substruct.
    */
  def getSubstructRegs: Seq[(String, Int)] =
    regList.toSeq.filter(_.substruct).map(entry => entry.name -> name2addr(entry.name))

  /** Return the name-address mapping of all registers.
    */
  def getAllRegs: Seq[(String, Int)]       =
    regList.toSeq.map(entry => entry.name -> name2addr(entry.name))

  def bindRegs(mcrIO: MCRIO): Unit = {
    // Distinct configuration registers are assigned to new word addresses.
    // The assumption that is an AXI4 lite bus implies they are 32b apart
    require((mcrIO.nastiXDataBits / 8) == bytesPerAddress)
    regList.zipWithIndex.foreach {
      case (e: DecoupledSinkEntry, index)   => mcrIO.bindDecoupledSink(e, index)
      case (e: DecoupledSourceEntry, index) => mcrIO.bindDecoupledSource(e, index)
      case (e: RegisterEntry, index)        => mcrIO.bindReg(e, index)
    }
  }

  /** Append the C++ representation of the address map to a string builder.
    *
    * @param base
    *   Base address of the widget MMIO registers.
    * @param sb
    *   Builder to append to.
    */
  def genAddressMap(base: BigInt, sb: StringBuilder): Unit = {
    def emitArrays(regs: Seq[(MCRMapEntry, BigInt)]): Unit = {
      regs.foreach { case (reg, addr) =>
        sb.append(s"      { ${CStrLit(reg.name).toC}, ${addr} },\\\n")
      }
    }

    val regAddrs  = regList.map(reg => reg -> (base + lookupAddress(reg.name).get))
    val readRegs  = regAddrs.filter(_._1.permissions.readable)
    val writeRegs = regAddrs.filter(_._1.permissions.writeable)

    sb.append(s"  AddressMap{\n")
    sb.append(s"    std::vector<std::pair<std::string, uint32_t>>{\n")
    emitArrays(readRegs.toSeq)
    sb.append(s"    },\n")
    sb.append(s"    std::vector<std::pair<std::string, uint32_t>>{\n")
    emitArrays(writeRegs.toSeq)
    sb.append(s"    }\n")
    sb.append(s"  }")
  }

  def printCRs: Unit = {
    regList.zipWithIndex.foreach { case (entry, i) => println(s"Name: ${entry.name}, Addr: $i") }
  }
}

class MCRIO(numCRs: Int)(implicit p: Parameters) extends NastiBundle()(p) {
  val read  = Vec(numCRs, Flipped(Decoupled(UInt(nastiXDataBits.W))))
  val write = Vec(numCRs, Decoupled(UInt(nastiXDataBits.W)))
  val wstrb = Output(UInt(nastiWStrobeBits.W))

  // Translates a static address into an index into the vecs above
  def toIndex(addr: Int): Int = addr >> log2Ceil(nastiXDataBits / 8)

  // Using a static address. determine if the associated register is being written to in the current cycle.
  def activeWriteToAddress(addr: Int): Bool = write(toIndex(addr)).valid

  def bindReg(reg: RegisterEntry, index: Int): Unit = {
    if (reg.permissions.writeable) {
      when(write(index).valid) {
        reg.node := write(index).bits
      }
    } else {
      assert(write(index).valid =/= true.B, s"Register ${reg.name} is read only")
    }

    if (reg.permissions.readable) {
      read(index).bits := reg.node
    } else {
      assert(read(index).ready === false.B, "Register ${reg.name} is write only")
    }

    read(index).valid  := true.B
    write(index).ready := true.B
  }

  def bindDecoupledSink(channel: DecoupledSinkEntry, index: Int): Unit = {
    channel.node <> write(index)
    assert(read(index).ready === false.B, "Can only write to this decoupled sink")
  }

  def bindDecoupledSource(channel: DecoupledSourceEntry, index: Int): Unit = {
    read(index) <> channel.node
    assert(write(index).valid =/= true.B, "Can only read from this decoupled source")
  }

}

class MCRFile(numRegs: Int)(implicit p: Parameters) extends NastiModule()(p) {
  val io = IO(new Bundle {
    val nasti = Flipped(new NastiIO)
    val mcr   = new MCRIO(numRegs)
  })

  //TODO: Just use a damn state machine.
  val rValid    = RegInit(false.B)
  val arFired   = RegInit(false.B)
  val awFired   = RegInit(false.B)
  val wFired    = RegInit(false.B)
  val wCommited = RegInit(false.B)
  val bId       = Reg(UInt(p(NastiKey).idBits.W))
  val rId       = Reg(UInt(p(NastiKey).idBits.W))
  val rData     = Reg(UInt(nastiXDataBits.W))
  val wData     = Reg(UInt(nastiXDataBits.W))
  val wIndex    = Reg(UInt(log2Up(numRegs).W))
  val rIndex    = Reg(UInt(log2Up(numRegs).W))
  val wStrb     = Reg(UInt(nastiWStrobeBits.W))

  when(io.nasti.aw.fire) {
    awFired := true.B
    wIndex  := io.nasti.aw.bits.addr >> log2Up(nastiWStrobeBits)
    bId     := io.nasti.aw.bits.id
    assert(io.nasti.aw.bits.len === 0.U)
  }

  when(io.nasti.w.fire) {
    wFired := true.B
    wData  := io.nasti.w.bits.data
    wStrb  := io.nasti.w.bits.strb
  }

  when(io.nasti.ar.fire) {
    arFired := true.B
    rIndex  := (io.nasti.ar.bits.addr >> log2Up(nastiWStrobeBits))(log2Up(numRegs) - 1, 0)
    rId     := io.nasti.ar.bits.id
    assert(io.nasti.ar.bits.len === 0.U, "MCRFile only support single beat reads")
  }

  when(io.nasti.r.fire) {
    arFired := false.B
  }

  when(io.nasti.b.fire) {
    awFired   := false.B
    wFired    := false.B
    wCommited := false.B
  }

  when(io.mcr.write(wIndex).fire) {
    wCommited := true.B
  }

  io.mcr.write.foreach { w => w.valid := false.B; w.bits := wData }
  io.mcr.write(wIndex).valid := awFired && wFired && ~wCommited
  io.mcr.read.zipWithIndex.foreach { case (decoupled, idx: Int) =>
    decoupled.ready := (rIndex === idx.U) && arFired && io.nasti.r.ready
  }

  io.nasti.r.bits  := NastiReadDataChannel(rId, io.mcr.read(rIndex).bits)
  io.nasti.r.valid := arFired && io.mcr.read(rIndex).valid

  io.nasti.b.bits  := NastiWriteResponseChannel(bId)
  io.nasti.b.valid := awFired && wFired && wCommited

  io.nasti.ar.ready := ~arFired
  io.nasti.aw.ready := ~awFired
  io.nasti.w.ready  := ~wFired
}

class CRIO(direction: ActualDirection, width: Int, val default: Int) extends Bundle {
  val value = (direction: @unchecked) match {
    case ActualDirection.Input  => Input(UInt(width.W))
    case ActualDirection.Output => Output(UInt(width.W))
  }
  def apply(dummy: Int = 0) = value
}

object CRIO {
  def apply(direction: ActualDirection, width: Int, default: Int) =
    new CRIO(direction, width, default)
}

class DecoupledCRIO[+T <: Data](gen: T) extends DecoupledIO[T](gen)
object DecoupledCRIO {
  def apply[T <: Data](gen: T): DecoupledCRIO[T] = new DecoupledCRIO(gen)
}

// I need the right name for this
object D2V           {
  def apply[T <: Data](in: DecoupledIO[T]): ValidIO[T] = {
    val v = Wire(Valid(in.bits.cloneType))
    v.bits  := in.bits
    v.valid := in.valid
    v
  }
}

object V2D {
  def apply[T <: Data](in: ValidIO[T]): DecoupledIO[T] = {
    val d = Wire(Decoupled(in.bits.cloneType))
    d.bits  := in.bits
    d.valid := in.valid
    d
  }
}

class IdentityModule[T <: Data](gen: T) extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(gen.cloneType)
    val out = gen.cloneType
  })

  io.out <> io.in
}

object IdentityModule {
  def apply[T <: Data](x: T): T = {
    val identity = Module(new IdentityModule(x))
    identity.io.in := x
    identity.io.out
  }
}
