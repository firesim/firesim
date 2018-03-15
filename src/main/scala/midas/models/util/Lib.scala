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
  def apply[T <: Data](data : T, scanEnable: Bool, scanIn: Bool): Bool = {
    val leaves = FlattenData(data)
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
  val value =  Reg(init=UInt(0, log2Up(n + 1)))
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

class MultiQueueIO[T <: Data](private val gen: T, numQueues: Int, entries: Int) extends
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
  io.count := DontCare
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
