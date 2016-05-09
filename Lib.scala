
package midas

import Chisel._

object dirExtracter {
  def dirProduct(context:Direction, field:Direction) : Direction = {
    if (context == INPUT) field.flip else field
  }

  def getFields[T <: Data] (gen: T, desiredDir: Direction,
                            baseName: String, parentDir: Direction = OUTPUT) :
                            Seq[(String, Data)] = {
    val currentDir = dirProduct(parentDir, gen.dir)
    val expandedName = if (baseName != "") baseName + "_" else ""
    gen match {
      case a : Bundle => {
        (a.elements map {e : (String, Data) => {
          getFields(e._2, desiredDir, s"${baseName}${e._1}", currentDir)}})
        }.asInstanceOf[Seq[Seq[(String,Data)]]].flatten
      case sint : SInt => if (currentDir == OUTPUT) Seq((baseName, sint.cloneType)) else Seq()
      case uint: UInt => if(currentDir == OUTPUT) Seq((baseName, uint.cloneType)) else Seq()
    }
  }
}

// Adapted from DecoupledIO in Chisel3
class HostDecoupledIO[+T <: Data](gen: T) extends Bundle
{
  val hostReady = Bool(INPUT)
  val hostValid = Bool(OUTPUT)
  val hostBits  = gen.cloneType
  override def cloneType: this.type = new HostDecoupledIO(gen).asInstanceOf[this.type]
}



/** Adds a ready-valid handshaking protocol to any interface.
  * The standard used is that the consumer uses the flipped interface.
  */
object HostDecoupled {
  def apply[T <: Data](gen: T): HostDecoupledIO[T] = new HostDecoupledIO(gen)
}


class HostReadyValid extends Bundle {
  val hostReady = Bool(INPUT)
  val hostValid = Bool(OUTPUT)
}

class HostPortIO[+T <: Data](gen: T) extends Bundle
{
  val hostIn = (new HostReadyValid).flip
  val hostOut = new HostReadyValid
  val hostBits  = gen.cloneType
  override def cloneType: this.type = new HostPortIO(gen).asInstanceOf[this.type]
}

object HostPort {
  def apply[T <: Data](gen: T): HostPortIO[T] = new HostPortIO(gen)
}


// The below is currently used to generate FIRRTL code for buildSimQueue
// TODO
//  - Actually use this code in buildSimQueue instead of one off FIRRTL
//  - This requires either a change to Bundle API (and possible others)
//      or using Scala macros (see quasiquotes)

/** An I/O Bundle with simple handshaking using valid and ready signals for data 'bits'*/
class MidasDecoupledIO[+T <: Data](gen: T) extends Bundle
{
  val hostReady = Bool(INPUT)
  val hostValid = Bool(OUTPUT)
  val hostBits  = gen.cloneType.asOutput
  def fire(dummy: Int = 0): Bool = hostReady && hostValid
  override def cloneType: this.type = new MidasDecoupledIO(gen).asInstanceOf[this.type]
}

/** Adds a hostReady-hostValid handshaking protocol to any interface.
  * The standard used is that the consumer uses the flipped interface.
  */
object MidasDecoupled {
  def apply[T <: Data](gen: T): MidasDecoupledIO[T] = new MidasDecoupledIO(gen)
}

/** An I/O Bundle for Queues
  * Borrowed from chisel3 util/Decoupled.scala
  * @param gen The type of data to queue
  * @param entries The max number of entries in the queue */
class MidasQueueIO[T <: Data](gen: T, entries: Int) extends Bundle
{
  /** I/O to enqueue data, is [[Chisel.DecoupledIO]] flipped */
  val enq   = MidasDecoupled(gen.cloneType).flip()
  /** I/O to enqueue data, is [[Chisel.DecoupledIO]]*/
  val deq   = MidasDecoupled(gen.cloneType)
  /** The current amount of data in the queue */
  val count = UInt(OUTPUT, log2Up(entries + 1))
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
class MidasInitQueue[T <: Data](gen: T,  entries: Int, init:() => T = null, numInitTokens:Int = 0) 
  extends Module {
  require(numInitTokens < entries, s"The capacity of the queue must be >= the number of initialization tokens")
  val io = new MidasQueueIO(gen.cloneType, entries)
  val queue = Module(new MidasQueue(gen.cloneType, entries))
  // This should only need to be 1 larger; but firrtl seems to optimize it away
  // when entries is set to 1
  val initTokensCount = Counter(numInitTokens+4)

  val doneInit = initTokensCount.value === UInt(numInitTokens)
  val initToken = init()
  val enqFire = queue.io.enq.fire()

  when(~doneInit && enqFire) {
    initTokensCount.inc()
  }

  queue.io.enq.hostBits := Mux(~doneInit,initToken,io.enq.hostBits)
  queue.io.enq.hostValid := ~doneInit || io.enq.hostValid
  io.enq.hostReady := doneInit && queue.io.enq.hostReady
  io.deq <> queue.io.deq
}
