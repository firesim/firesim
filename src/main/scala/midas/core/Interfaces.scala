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
  def fire(): Bool = hReady && hValid
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
  val hReady = Input(Bool())
  val hValid = Output(Bool())
  def fire(): Bool = hReady && hValid
}

// For use in the interface of EndpointWidgets
class HostPortIO[+T <: Data](gen: T) extends Bundle with midas.widgets.HasEndpointChannels {
  val fromHost = new HostReadyValid
  val toHost = Flipped(new HostReadyValid)
  val hBits  = gen

  override def cloneType: this.type = new HostPortIO(gen).asInstanceOf[this.type]

  private lazy val (ins, outs, _, _) = SimUtils.parsePorts(hBits)
  def inputWireChannels(): Seq[(Data, String)] = ins
  def outputWireChannels(): Seq[(Data, String)] = outs
}

object HostPort {
  def apply[T <: Data](gen: T): HostPortIO[T] = new HostPortIO(gen)
}
