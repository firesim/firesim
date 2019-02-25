// See LICENSE for license details.

package midas.models

import midas.core.{HostPort, MemNastiKey, IsRationalClockRatio, UnityClockRatio}
import midas.widgets._
import junctions._

import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.amba.axi4._

import chisel3._
import chisel3.util._
import chisel3.core.ActualDirection
import chisel3.core.DataMirror.directionOf

// Note: NASTI -> legacy rocket chip implementation of AXI4
case object MemModelKey extends Field[Parameters => FASEDMemoryTimingModel]
case object FasedAXI4Edge extends Field[Option[AXI4EdgeParameters]](None)

// A workaround for passing more information about the diplomatic graph to the
// memory model This drops the edge parameters into the bundle, which can be
// consumed by the endpoint
class AXI4BundleWithEdge(params: AXI4BundleParameters, val edge: AXI4EdgeParameters)
    extends AXI4Bundle(params) {
  override def cloneType() = new AXI4BundleWithEdge(params, edge).asInstanceOf[this.type]
}

object AXI4BundleWithEdge {
  // this is type returned by a diplomatic nodes's in() and out() methods
  def apply(tuple: (AXI4Bundle, AXI4EdgeParameters)) = tuple match {
    case (b, e) => new AXI4BundleWithEdge(b.params, e)
  }
}


abstract class SimMemIO extends Endpoint {
  override def widgetName = "MemModel"
  // This is hideous, but we want some means to get the widths of the target
  // interconnect so that we can pass that information to the widget the
  // endpoint will instantiate.
  var targetAXI4Widths = NastiParameters(0,0,0)
  var targetAXI4Edge: Option[AXI4EdgeParameters] = None
  var initialized = false
  override def add(name: String, channel: Data) {
    initialized = true
    super.add(name, channel)
    targetAXI4Widths = channel match {
      case axi4: AXI4BundleWithEdge => NastiParameters(axi4.r.bits.data.getWidth,
                                               axi4.ar.bits.addr.getWidth,
                                               axi4.ar.bits.id.getWidth)
      case axi4: AXI4Bundle => NastiParameters(axi4.r.bits.data.getWidth,
                                               axi4.ar.bits.addr.getWidth,
                                               axi4.ar.bits.id.getWidth)
      case axi4: NastiIO => NastiParameters(axi4.r.bits.data.getWidth,
                                            axi4.ar.bits.addr.getWidth,
                                            axi4.ar.bits.id.getWidth)
      case _ => throw new RuntimeException("Unexpected channel type passed to SimMemIO")
    }
    targetAXI4Edge = channel match {
      case b: AXI4BundleWithEdge => Some(b.edge)
      case _ => None
    }
  }

  private def getChannelAXI4Parameters = {
    scala.Predef.assert(initialized, "Widget instantiated without first binding a target channel.")
    targetAXI4Widths
  }

  def widget(p: Parameters): FASEDMemoryTimingModel = {
    val param = p.alterPartial({
      case NastiKey => getChannelAXI4Parameters
      case FasedAXI4Edge => targetAXI4Edge
    })
    p(MemModelKey)(param)
  }
}

class FASEDNastiEndpoint(
    override val clockRatio: IsRationalClockRatio = UnityClockRatio
  ) extends SimMemIO {
  def matchType(data: Data) = data match {
    case channel: NastiIO =>
      directionOf(channel.w.valid) == ActualDirection.Output
    case _ => false
  }
}

class FASEDAXI4Endpoint(
    override val clockRatio: IsRationalClockRatio = UnityClockRatio
  ) extends SimMemIO {
  def matchType(data: Data) = data match {
    case channel: AXI4Bundle =>
      directionOf(channel.w.valid) == ActualDirection.Output
    case _ => false
  }
}
