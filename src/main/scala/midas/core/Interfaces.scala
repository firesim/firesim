// See LICENSE for license details.

package midas
package core

import chisel3._
import chisel3.util.ReadyValidIO

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
// We're using a Record here because reflection in Bundle prematurely initializes our lazy vals
class HostPortIO[+T <: Data](gen: T) extends Record with midas.widgets.HasEndpointChannels {
  val fromHost = new HostReadyValid
  val toHost = Flipped(new HostReadyValid)
  val hBits  = gen

  val elements = collection.immutable.ListMap(Seq("fromHost" -> fromHost, "toHost" -> toHost, "hBits" -> hBits):_*)

  override def cloneType: this.type = new HostPortIO(gen).asInstanceOf[this.type]

  // These are lazy because parsePorts needs a directioned gen; these can be called once 
  // this Record has been bound to Hardware
  private lazy val (ins, outs, rvIns, rvOuts) = SimUtils.parsePorts(gen, alsoFlattenRVPorts = false)
  def inputWireChannels(): Seq[(Data, String)] = ins
  def outputWireChannels(): Seq[(Data, String)] = outs
  def inputRVChannels(): Seq[(ReadyValidIO[Data], String)] = rvIns
  def outputRVChannels(): Seq[(ReadyValidIO[Data], String)] = rvOuts
  lazy val name2Wire = Map((ins ++ outs).map({ case (wire, name) => name -> wire }):_*)
  lazy val name2ReadyValid = Map((rvIns ++ rvOuts).map({ case (wire, name) => name -> wire }):_*)
}

object HostPort {
  def apply[T <: Data](gen: T): HostPortIO[T] = new HostPortIO(gen)
}
