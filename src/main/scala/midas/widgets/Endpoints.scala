// See LICENSE for license details.

package midas
package widgets

import midas.core.{IsRationalClockRatio, UnityClockRatio, HostPort, HostPortIO, SimUtils}

import freechips.rocketchip.config.Parameters

import chisel3._
import chisel3.util._
import chisel3.core.ActualDirection
import chisel3.core.DataMirror.directionOf

import scala.collection.mutable.{ArrayBuffer, HashSet}

/* Endpoint
 *
 * Endpoints are widgets that operate directly on token streams moving to and
 * from the transformed-RTL model.
 *
 * Endpoints extend Widget to add an IO that includes a HostPort[T <: Data] which
 * contains bidriectional channels for token-flow moving from the transformed-RTL
 * model to the endpoint ("toHost"), and from the endpoint to the transformed
 * RTL model ("fromHost")
 *
 * Endpoints must also define a matcher-class that extends "trait Endpoint"
 * This guides MIDAS during platform-mapping, by matching on ports on the transformed-RTL 
 * whose Chisel-type matches the the Chisel-type of your endpoint's HostPort
 * and thus, which token streams, should be connected to your Endpoint. carried
 * by token
 */

abstract class EndpointWidgetIO(implicit p: Parameters) extends WidgetIO()(p) {
  def hPort: HostPortIO[Data] // Tokenized port moving between the endpoint the target-RTL
  val tReset = Flipped(Decoupled(Bool()))
}

abstract class EndpointWidget(implicit p: Parameters) extends Widget()(p) {
  override def io: EndpointWidgetIO
}


trait Endpoint {
  protected val channels = ArrayBuffer[(String, Record)]()
  protected val wires = HashSet[Bits]()
  def clockRatio: IsRationalClockRatio = UnityClockRatio
  def matchType(data: Data): Boolean
  def widget(p: Parameters): EndpointWidget
  def widgetName: String = getClass.getSimpleName
  final def size = channels.size
  final def apply(wire: Bits) = wires(wire)
  final def apply(i: Int) = channels(i)
  def add(name: String, channel: Data) {
    val (ins, outs) = SimUtils.parsePorts(channel)
    wires ++= (ins ++ outs).unzip._1
    channels += (name -> channel.asInstanceOf[Record])
  }
}

case class EndpointMap(endpoints: Seq[Endpoint]) {
  def get(data: Data) = endpoints find (_ matchType data)
  def ++(x: EndpointMap) = EndpointMap(endpoints ++ x.endpoints) 
}
