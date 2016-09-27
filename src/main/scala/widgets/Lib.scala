
package midas

import Chisel._
import cde.{Parameters, Field}
import junctions._
import scala.collection.mutable.HashMap
import scala.collection.mutable.ArrayBuffer

/** Takes an arbtirary Data type, and flattens it (akin to .flatten()).
  * Returns a Seq of the leaf nodes with their absolute/final direction.
  */
object FlattenData {
  def dirProduct(context: Direction, field: Direction): Direction = {
    if(context == INPUT) field.flip else field
  }

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
  val io = new SatUpDownCounterIO(n)
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
  val io = new MultiQueueIO(gen, numQueues, entries)
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

/** A hardware module implementing a Queue
  * Modified from chisel3 util/Decoupled.scala
  * @param gen The type of data to queue
  * @param entries The max number of entries in the queue
  * @param pipe True if a single entry queue can run at full throughput (like a pipeline). The ''hReady'' signals are
  * combinationally coupled.
  * @param flow True if the inputs can be consumed on the same cycle (the inputs "flow" through the queue immediately).
  * The ''hValid'' signals are coupled.
  *
  * Example usage:
  *    {{{ val q = new Queue(UInt(), 16)
  *    q.io.enq <> producer.io.out
  *    consumer.io.in <> q.io.deq }}}
  */
class MidasQueue[T <: Data](gen: T, val entries: Int,
                       pipe: Boolean = false,
                       flow: Boolean = false,
                       _reset: Bool = null) extends Module(_reset=_reset)
{
  val io = new MidasQueueIO(gen, entries)

  val ram = Mem(entries, gen)
  val enq_ptr = Counter(entries)
  val deq_ptr = Counter(entries)
  val maybe_full = Reg(init=Bool(false))

  val ptr_match = enq_ptr.value === deq_ptr.value
  val empty = ptr_match && !maybe_full
  val full = ptr_match && maybe_full
  val maybe_flow = Bool(flow) && empty
  val do_flow = maybe_flow && io.deq.hReady

  val do_enq = io.enq.hReady && io.enq.hValid && !do_flow
  val do_deq = io.deq.hReady && io.deq.hValid && !do_flow
  when (do_enq) {
    ram(enq_ptr.value) := io.enq.hBits
    enq_ptr.inc()
  }
  when (do_deq) {
    deq_ptr.inc()
  }
  when (do_enq != do_deq) {
    maybe_full := do_enq
  }

  io.deq.hValid := !empty || Bool(flow) && io.enq.hValid
  io.enq.hReady := !full || Bool(pipe) && io.deq.hReady
  io.deq.hBits := Mux(maybe_flow, io.enq.hBits, ram(deq_ptr.value))

  val ptr_diff = enq_ptr.value - deq_ptr.value
  if (isPow2(entries)) {
    io.count := Cat(maybe_full && ptr_match, ptr_diff)
  } else {
    io.count := Mux(ptr_match,
                    Mux(maybe_full,
                      UInt(entries), UInt(0)),
                    Mux(deq_ptr.value > enq_ptr.value,
                      UInt(entries) + ptr_diff, ptr_diff))
  }
}

private class FooBar extends Bundle {
  val foo = UInt(width = 4)
  val bar = UInt(width = 4).flip
}

private class GenSimQueue extends Module {
  val io = new Bundle { }
  val queue = Module(new MidasQueue((new FooBar).cloneType, 4))
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

// A simple implementation of a simulation queue that injects a set of
// simulation tokens into on reset
/*
class MidasInitQueue[T <: Data](gen: T,  entries: Int, init:() => T = null, numInitTokens:Int = 0) extends Module {
  require(numInitTokens < entries, s"The capacity of the queue must be >= the number of initialization tokens")
  val io = new MidasQueueIO(gen.cloneType, entries)
  val queue = Module(new Queue(gen.cloneType, entries))
  queue.reset := io.ctrl.simReset

  // Tie off the control signals to default values
  io.ctrl := MidasControl()

  // This should only need to be 1 larger; but firrtl seems to optimize it away
  // when entries is set to 1
  val initTokensCount = Counter(numInitTokens+4)
  val doneInit = Reg(init = Bool(false))
  val simRunning = Reg(init = Bool(false))
  io.ctrl.simResetDone := doneInit

  val enqFire = queue.io.enq.fire()

  // Control register sequencing
  when(io.ctrl.simReset) {
    initTokensCount.value := UInt(0)
    doneInit := Bool(false)
    simRunning := Bool(false)
  }.elsewhen(~doneInit) {
    initTokensCount.inc()
    doneInit := initTokensCount.value === UInt(numInitTokens - 1)
  }.elsewhen(io.ctrl.go) {
    simRunning := Bool(true)
  }

  val initToken = init()
  queue.io.enq.bits := Mux(~simRunning,initToken,io.enq.hBits)
  queue.io.enq.valid := ~doneInit || io.enq.hValid
  io.enq.hReady := simRunning && queue.io.enq.ready
  io.deq.hBits := queue.io.deq.bits
  io.deq.hValid := simRunning && queue.io.deq.valid
  queue.io.deq.ready := io.deq.hReady
} */

/** Stores a map between SCR file names and address in the SCR file, which can
  * later be dumped to a header file for the test bench. */
class MCRFileMap() {
  private val name2addr = HashMap.empty[String, Int]
  private val regList = ArrayBuffer.empty[Data]

  def allocate(reg: Data, name: String): Int = {
    Predef.assert(!name2addr.contains(name), "name already allocated")
    val address = name2addr.size
    name2addr += (name -> address)
    regList.append(reg)
    address
  }

  def lookupAddress(name: String): Option[Int] = name2addr.get(name)

  def numRegs(): Int = regList.size

  def bindRegs(mcrIO: MCRIO): Unit = {
    (regList.toSeq.zipWithIndex) foreach {
      case(reg: Data, addr: Int) => mcrIO.bindReg(reg, addr)
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
}

class MCRIO(numCRs: Int)(implicit p: Parameters) extends NastiBundle()(p) {
  val rdata = Vec(numCRs, Bits(INPUT, nastiXDataBits))
  val wen = Bool(OUTPUT)
  val waddr = UInt(OUTPUT, log2Up(numCRs))
  val wdata = Bits(OUTPUT, nastiXDataBits)
  val wstrb = Bits(OUTPUT, nastiWStrobeBits)

  def bindReg(reg: Data, addr: Int): Unit = {
    when(wen && (waddr === UInt(addr))) {
      reg := (Vec.tabulate(nastiWStrobeBits)(i => Mux(wstrb(i),
        wdata.toBits()(8*(i+1)-1, i*8), reg.toBits()(8*(i+1)-1, 8*i)))).toBits().asUInt()
    }
    rdata(addr) := reg
  }
}

class MCRFile(numRegs: Int)(implicit p: Parameters) extends NastiModule()(p) {
  val io = new Bundle {
    val nasti = (new NastiIO).flip
    val mcr = new MCRIO(numRegs)
  }

  val rValid = Reg(init = Bool(false))
  val awFired = Reg(init = Bool(false))
  val wFired = Reg(init = Bool(false))
  val bId = Reg(UInt(width = p(NastiKey).idBits))
  val rId = Reg(UInt(width = p(NastiKey).idBits))
  val rData = Reg(UInt(width = nastiXDataBits))
  val wData = Reg(UInt(width = nastiXDataBits))
  val wAddr = Reg(UInt(width = nastiXAddrBits))
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
    rValid := Bool(true)
    rData := io.mcr.rdata(io.nasti.ar.bits.addr >> log2Up(nastiXDataBits/8))(log2Up(numRegs)-1,0)
    rId := io.nasti.ar.bits.id
  }

  when(io.nasti.r.fire()) {
    rValid := Bool(false)
  }

  when (io.nasti.b.fire()) {
    awFired := Bool(false)
    wFired := Bool(false)
  }

  io.nasti.r.bits := NastiReadDataChannel(rId, rData)
  io.nasti.r.valid := rValid

  io.nasti.b.bits := NastiWriteResponseChannel(bId)
  io.nasti.b.valid := awFired && wFired

  io.nasti.ar.ready := ~rValid
  io.nasti.aw.ready := ~awFired
  io.nasti.w.ready := ~wFired

  //Use b fire just because its a convienent way to track one write transaction
  io.mcr.wen := io.nasti.b.fire()
  io.mcr.wdata := wData
  io.mcr.wstrb := wStrb
  io.mcr.waddr := wAddr
}
