
package midas

import Chisel._

// Adapted from DecoupledIO in Chisel3
class HostDecoupledIO[+T <: Data](gen: T) extends Bundle
{
  val hostReady = Bool(INPUT)
  val hostValid = Bool(OUTPUT)
  val hostBits  = gen.cloneType
  override def cloneType: this.type =
    new HostDecoupledIO(gen).asInstanceOf[this.type]
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
  def fire(dummy: Int = 0): Bool = hostReady && hostValid
}

class HostPortIO[+T <: Data](gen: T) extends Bundle
{
  val hostIn = (new HostReadyValid).flip
  val hostOut = new HostReadyValid
  val hostBits  = gen.cloneType
  override def cloneType: this.type =
    new HostPortIO(gen).asInstanceOf[this.type]
}

object HostPort {
  def apply[T <: Data](gen: T): HostPortIO[T] = new HostPortIO(gen)
}


// The below is currently used to generate FIRRTL code for buildSimQueue
// TODO
//  - Actually use this code in buildSimQueue instead of one off FIRRTL
//  - This requires either a change to Bundle API (and possible others)
//      or using Scala macros (see quasiquotes)

/** An I/O Bundle with simple handshaking using valid and ready signals for
  * data 'bits'
  */
class MidasDecoupledIO[+T <: Data](gen: T) extends Bundle
{
  val hostReady = Bool(INPUT)
  val hostValid = Bool(OUTPUT)
  val hostBits  = gen.cloneType.asOutput
  def fire(dummy: Int = 0): Bool = hostReady && hostValid
  override def cloneType: this.type =
    new MidasDecoupledIO(gen).asInstanceOf[this.type]
}

/** Adds a hostReady-hostValid handshaking protocol to any interface.
  * The standard used is that the consumer uses the flipped interface.
  */
object MidasDecoupled {
  def apply[T <: Data](gen: T): MidasDecoupledIO[T] = new MidasDecoupledIO(gen)
}


/** An I/O Bundle for MIDAS modules
  * WIP - This only supports a subset of the features we discussed in the deep
  *       dive for the purposes of the demo
  *
  * reset - driven by the simulation controller to put the simulator into it's
  *   initial state. Distinct from the target reset (which is asserted during
  *   execution) in most cases this can drive the module's implicit reset
  * resetDone -
  *   asserted by simulation modules when they've completed their initialization
  * go - driven by the simulation controller, begins the simulation, by
  *   permitting the queues to enq and deq simulation tokens
  * done - driven by a subset of simulation modules, that have sufficient
  *   knowledge of the target design to decide if the simulation should
  *   complete. Ultimately the master holds the reigns
  *
  */
class MidasControlIO extends Bundle {
  val simReset = Bool(INPUT)
  val simResetDone = Bool(OUTPUT)
  val go = Bool(INPUT)
  val done = Bool(OUTPUT)

  def fanout(slaves : Seq[MidasControlIO]) = {
    val trunk = MidasControl()
    slaves map (trunk <> _)
    trunk.done := slaves map (_.done) reduce (_ && _)
    trunk.simResetDone := slaves map (_.simResetDone) reduce (_ && _)
    trunk
  }
}

object MidasControl {
  def apply(doneVal: Bool = Bool(true), resetDoneVal: Bool = Bool(true)) :
  MidasControlIO = {
    val mc = Wire(new MidasControlIO())
    mc.done := doneVal
    mc.simResetDone := resetDoneVal
    mc
  }
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
  /** The MIDAS control interface */
  val ctrl = new MidasControlIO
}
