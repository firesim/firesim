
package midas

import Chisel._
import cde.{Parameters, Field}
import junctions._
import scala.collection.mutable.HashMap
import scala.collection.mutable.ArrayBuffer


case object MidasBaseAddr extends Field[BigInt]

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
  when (io.enqAddr === io.deqAddr) {
    when(do_enq != do_deq) {
    maybe_full(io.enqAddr) := do_enq
    }
  }.otherwise {
    when(do_enq) {
      maybe_full(io.enqAddr) := Bool(true)
    }
    when (do_deq) {
      maybe_full(io.deqAddr) := Bool(false)
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
  * @param pipe True if a single entry queue can run at full throughput (like a pipeline). The ''hostReady'' signals are
  * combinationally coupled.
  * @param flow True if the inputs can be consumed on the same cycle (the inputs "flow" through the queue immediately).
  * The ''hostValid'' signals are coupled.
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
  val do_flow = maybe_flow && io.deq.hostReady

  val do_enq = io.enq.hostReady && io.enq.hostValid && !do_flow
  val do_deq = io.deq.hostReady && io.deq.hostValid && !do_flow
  when (do_enq) {
    ram(enq_ptr.value) := io.enq.hostBits
    enq_ptr.inc()
  }
  when (do_deq) {
    deq_ptr.inc()
  }
  when (do_enq != do_deq) {
    maybe_full := do_enq
  }

  io.deq.hostValid := !empty || Bool(flow) && io.enq.hostValid
  io.enq.hostReady := !full || Bool(pipe) && io.deq.hostReady
  io.deq.hostBits := Mux(maybe_flow, io.enq.hostBits, ram(deq_ptr.value))

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
    output.hostValid := a.hostValid || b.hostValid
    output.hostBits := Mux(sel, a.hostBits, b.hostBits)
    a.hostReady := sel && output.hostReady
    b.hostReady := ~sel && output.hostReady
    output
  }
}

// A simple implementation of a simulation queue that injects a set of
// simulation tokens into on reset
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
  queue.io.enq.bits := Mux(~simRunning,initToken,io.enq.hostBits)
  queue.io.enq.valid := ~doneInit || io.enq.hostValid
  io.enq.hostReady := simRunning && queue.io.enq.ready
  io.deq.hostBits := queue.io.deq.bits
  io.deq.hostValid := simRunning && queue.io.deq.valid
  queue.io.deq.ready := io.deq.hostReady
}

case object MidasKey extends Field[MidasParameters]
// TODO: better name
case class MidasParameters(width: Int, offsetBits: Int, mcrDataBits: Int, nMCR: Int = 64)

trait HasMidasParameters {
  implicit val p: Parameters
  val nMCR = 4
  val mcrAddrBits = log2Up(nMCR)
  val mcrDataBits = 64
  val mcrDataBytes = mcrDataBits / 8
  val offsetBits = 0
}

abstract class MidasModule(implicit val p: Parameters) extends Module with HasMidasParameters
abstract class MidasBundle(implicit val p: Parameters) extends ParameterizedBundle()(p)
  with HasMidasParameters
/** Stores a map between SCR file names and address in the SCR file, which can
  * later be dumped to a header file for the test bench. */
class MCRFileMap(prefix: String, maxAddress: Int, baseAddress: BigInt, width: Int) {
  private val addr2name = HashMap.empty[Int, String]
  private val name2addr = HashMap.empty[String, Int]

  def allocate(address: Int, name: String): Int = {
    Predef.assert(!addr2name.contains(address), "address already allocated")
    Predef.assert(!name2addr.contains(name), "name already allocated")
    Predef.assert(address < maxAddress, "address too large")
    addr2name += (address -> name)
    name2addr += (name -> address)
    println(prefix + ": %x -> ".format(baseAddress + address) + name)
    address
  }

  def allocate(name: String): Int = {
    val addr = (0 until maxAddress by width).filter{ addr => !addr2name.contains(addr) }(0)
    allocate(addr, name)
  }

  def as_c_header(): String = {
    addr2name.map{ case(address, name) =>
      List(
        "#define " + prefix + "__" + name + "__PADDR  0x%x".format(baseAddress + address),
        "#define " + prefix + "__" + name + "__OFFSET 0x%x".format(address)
      )
    }.flatten.mkString("\n") + "\n"
  }
}

class MCRIO(map: MCRFileMap)(implicit p: Parameters) extends MidasBundle()(p) {
  val rdata = Vec(nMCR, Bits(INPUT, mcrDataBits))
  val wen = Bool(OUTPUT)
  val waddr = UInt(OUTPUT, log2Up(nMCR))
  val wdata = Bits(OUTPUT, mcrDataBits)

  def attach(regs: Seq[Data], name_base: String): Seq[Data] = {
    regs.zipWithIndex.map{ case(reg, i) => attach(reg, name_base + "__" + i) }
  }

  def attach(reg: Data, name: String): Data = {
    val addr = map.allocate(name)
    when (wen && (waddr === UInt(addr>>log2Up(mcrDataBytes)))) {
      reg := wdata
    }
    rdata(addr>>log2Up(mcrDataBytes)) := reg
    reg
  }

  def allocate(address: Int, name: String): Unit = {
    map.allocate(address, name)
  }
}
// width = width of the axi bus in bytes
class MCRFile(prefix: String, baseAddress: BigInt)(implicit p: Parameters) extends MidasModule()(p) 
  with HasNastiParameters{
  val map = new MCRFileMap(prefix, 64, baseAddress, mcrDataBytes)
  AllMCRFiles += map

  val io = new Bundle {
    val nasti = (new NastiIO).flip
    val mcr = new MCRIO(map)
  }

  // To ensure reads index correctly
  require(isPow2(nMCR))

  val rValid = Reg(init = Bool(false))
  val awFired = Reg(init = Bool(false))
  val wFired = Reg(init = Bool(false))
  val bId = Reg(UInt(width = p(NastiKey).idBits))
  val rId = Reg(UInt(width = p(NastiKey).idBits))
  val rData = Reg(UInt(width = nastiXDataBits))
  val wData = Reg(UInt(width = nastiXDataBits))
  val wAddr = Reg(UInt(width = nastiXAddrBits))

  when(io.nasti.aw.fire()){
    awFired := Bool(true)
    wAddr := io.nasti.aw.bits.addr >> log2Up(mcrDataBytes)
    bId := io.nasti.aw.bits.id
    assert(io.nasti.aw.bits.len === UInt(0))
  }

  when(io.nasti.w.fire()){
    wFired := Bool(true)
    wData := io.nasti.w.bits.data
  }

  when(io.nasti.ar.fire()) {
    rValid := Bool(true)
    rData := io.mcr.rdata((io.nasti.ar.bits.addr >> log2Up(mcrDataBytes))&(UInt(nMCR-1)))
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
  io.mcr.waddr := wAddr
}

class MidasSimulationController(implicit p:Parameters) extends MidasModule()(p) {
  val io = new Bundle {
    val nasti = (new NastiIO).flip
    val ctrl = (new MidasControlIO).flip
  }

  val mcrFile = Module(new MCRFile("SIMULATION_MASTER", p(MidasBaseAddr)))
  val done = Reg(init = UInt(0))
  val resetDone = Reg(init = UInt(0))
  val simReset = Reg(init  = UInt(0))
  val go = Reg(init = UInt(0))
  mcrFile.io.mcr.attach(simReset, "RESET")
  mcrFile.io.mcr.attach(resetDone, "RESET_DONE")
  mcrFile.io.mcr.attach(go, "GO")
  mcrFile.io.mcr.attach(done, "DONE")
  mcrFile.io.nasti <> io.nasti

  // TODO: Make these booleans
  // Single cycle pulse
  when (go != UInt(0)) {
    go := UInt(0)
  }

  when (io.ctrl.simResetDone) {
    resetDone := UInt(1)
  }

  when (io.ctrl.done) {
    done := UInt(1)
  }

  when (simReset != UInt(0)) {
    resetDone := UInt(0)
    go := UInt(0)
    done := UInt(0)
    simReset := UInt(0)
  }

  io.ctrl.simReset := simReset != UInt(0)
  io.ctrl.go := go != UInt(0)
}


/** Every elaborated SCR file ends up in this global arry so it can be printed
  * out later. */
object AllMCRFiles {
  private var maps = ArrayBuffer.empty[MCRFileMap]

  def +=(map: MCRFileMap): Unit = { maps += map }
  def foreach( f: (MCRFileMap => Unit) ): Unit = { maps.foreach{ m => f(m) } }
}
