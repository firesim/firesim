// See LICENSE for license details.

package midas
package core

import freechips.rocketchip.amba.axi4.AXI4Bundle
import freechips.rocketchip.config.Parameters

import chisel3._
import chisel3.util._
import chisel3.experimental.{Direction}
import chisel3.experimental.DataMirror.{directionOf}
import widgets._
import junctions.{NastiIO, NastiKey, NastiParameters}
import scala.collection.mutable.{ArrayBuffer, HashSet}

trait Endpoint {
  val channels = ArrayBuffer[(String, Record)]()
  val wires = HashSet[Element]()
  def clockRatio: IsRationalClockRatio = UnityClockRatio
  def matchType(data: Data): Boolean
  def widget(p: Parameters): EndpointWidget
  def widgetName: String = getClass.getSimpleName
  final def size = channels.size
  final def apply(wire: Element) = wires(wire)
  final def apply(i: Int) = channels(i)
  def add(name: String, channel: Data) {
    val (ins, outs) = SimUtils.parsePorts(channel)
    wires ++= (ins ++ outs).unzip._1
    channels += (name -> channel.asInstanceOf[Record])
  }

  // Finds all of the target ReadyValid bundles sourced or sunk by the target
  // input => sunk by the target
  private def findRVChannels(dir: Direction): Seq[(String, ReadyValidIO[Data])] =
    channels.flatMap({ case (prefix, data) => data.elements.toSeq.collect({
        case (name, rv: ReadyValidIO[_]) if directionOf(rv.valid) == dir => s"${prefix}_${name}" -> rv
      })
  })

  lazy val readyValidOutputs = findRVChannels(Direction.Output)
  lazy val readyValidInputs = findRVChannels(Direction.Input)

}

abstract class SimMemIO extends Endpoint {
  // This is hideous, but we want some means to get the widths of the target
  // interconnect so that we can pass that information to the widget the
  // endpoint will instantiate.
  var targetAXI4Widths = NastiParameters(0,0,0)
  var initialized = false
  override def add(name: String, channel: Data) {
    initialized = true
    super.add(name, channel)
    targetAXI4Widths = channel match {
      case axi4: AXI4Bundle => NastiParameters(axi4.r.bits.data.getWidth,
                                               axi4.ar.bits.addr.getWidth,
                                               axi4.ar.bits.id.getWidth)
      case axi4: NastiIO => NastiParameters(axi4.r.bits.data.getWidth,
                                            axi4.ar.bits.addr.getWidth,
                                            axi4.ar.bits.id.getWidth)
      case _ => throw new RuntimeException("Unexpected channel type passed to SimMemIO")
    }
  }

  private def getChannelAXI4Parameters = {
    scala.Predef.assert(initialized, "Widget instantiated without first binding a target channel.")
    targetAXI4Widths
  }

  def widget(p: Parameters) = {
    val param = p alterPartial ({ case NastiKey => getChannelAXI4Parameters })
    (p(MemModelKey): @unchecked) match {
      case Some(modelGen) => modelGen(param)
      case None => new NastiWidget()(param)
    }
  }
}

class SimNastiMemIO(
    override val clockRatio: IsRationalClockRatio = UnityClockRatio
  ) extends SimMemIO {
  def matchType(data: Data) = data match {
    case channel: NastiIO =>
      directionOf(channel.w.valid) == Direction.Output
    case _ => false
  }
}

class SimAXI4MemIO(
    override val clockRatio: IsRationalClockRatio = UnityClockRatio
  ) extends SimMemIO {
  def matchType(data: Data) = data match {
    case channel: AXI4Bundle =>
      directionOf(channel.w.valid) == Direction.Output
    case _ => false
  }
}

case class EndpointMap(endpoints: Seq[Endpoint]) {
  def get(data: Data) = endpoints find (_ matchType data)
  def ++(x: EndpointMap) = EndpointMap(endpoints ++ x.endpoints) 
}
