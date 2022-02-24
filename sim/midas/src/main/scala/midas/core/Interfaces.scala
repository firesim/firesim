// See LICENSE for license details.

package midas
package core

import chisel3._


// Adapted from DecoupledIO in Chisel3
class HostDecoupledIO[+T <: Data](gen: T) extends Bundle
{
  val hReady = Input(Bool())
  val hValid = Output(Bool())
  val hBits  = gen.cloneType
  def fire: Bool = hReady && hValid
}

/** Adds a ready-valid handshaking protocol to any interface.
  * The standard used is that the consumer uses the flipped interface.
  */
object HostDecoupled {
  def apply[T <: Data](gen: T): HostDecoupledIO[T] = new HostDecoupledIO(gen)
}

class HostReadyValid extends Bundle {
  val hReady = Input(Bool())
  val hValid = Output(Bool())
  def fire: Bool = hReady && hValid
}
