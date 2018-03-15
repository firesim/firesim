// See LICENSE for license details.

package midas
package core

import freechips.rocketchip.config.Parameters

import chisel3._
import chisel3.util._
import chisel3.core.ActualDirection
import chisel3.core.DataMirror.directionOf
import widgets._
import junctions.{NastiIO, NastiKey, NastiParameters}
import scala.collection.mutable.{ArrayBuffer, HashSet}

trait Endpoint {
  protected val channels = ArrayBuffer[(String, Record)]()
  protected val wires = HashSet[Bits]()
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

abstract class SimMemIO extends Endpoint {
  // This is hideous, but we want some means to get the widths of the target
  // interconnect so that we can pass that information to the widget the
  // endpoint will instantiate.
  private var targetAXI4Widths = NastiParameters(0,0,0)
  private var initialized = false
  protected def inferTargetAXI4Widths(channel: Data) =
    channel match {
      case axi4: NastiIO => NastiParameters(axi4.r.bits.data.getWidth,
                                            axi4.ar.bits.addr.getWidth,
                                            axi4.ar.bits.id.getWidth)
      case _ => throw new RuntimeException("Unexpected channel type passed to SimMemIO")
    }

  override def add(name: String, channel: Data) {
    initialized = true
    super.add(name, channel)
    targetAXI4Widths = inferTargetAXI4Widths(channel)
  }

  private def getChannelAXI4Parameters = {
    scala.Predef.assert(initialized, "Widget instantiated without first binding a target channel.")
    targetAXI4Widths
  }

  def widget(p: Parameters) = {
    // We can't handle width adaption yet
    scala.Predef.assert(p(MemNastiKey).dataBits == getChannelAXI4Parameters.dataBits)
    val param = p alterPartial ({ case NastiKey => getChannelAXI4Parameters })
    (p(MemModelKey): @unchecked) match {
      case Some(modelGen) => modelGen(param)
      case None => new NastiWidget()(param)
    }
  }
}

class SimNastiMemIO extends SimMemIO {
  def matchType(data: Data) = data match {
    case channel: NastiIO =>
      directionOf(channel.w.valid) == ActualDirection.Output
    case _ => false
  }
}

case class EndpointMap(endpoints: Seq[Endpoint]) {
  def get(data: Data) = endpoints find (_ matchType data)
  def ++(x: EndpointMap) = EndpointMap(endpoints ++ x.endpoints) 
}
