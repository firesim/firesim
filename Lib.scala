
package midas

import Chisel._

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

