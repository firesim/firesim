// See LICENSE for license details.

package midas.models

import midas.core.{HostPort, MemNastiKey, IsRationalClockRatio, UnityClockRatio}
import midas.widgets._
import junctions._

import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.amba.axi4._

import chisel3._
import chisel3.util._
import chisel3.experimental.{Direction}
import chisel3.experimental.DataMirror.{directionOf}

// Note: NASTI -> legacy rocket chip implementation of AXI4
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
  def fromNode(ports: Seq[(AXI4Bundle, AXI4EdgeParameters)]): Seq[AXI4BundleWithEdge] = ports.map({ 
    case (bundle, edge) => new AXI4BundleWithEdge(bundle.params, edge)
  })
//  // Finds all of the target ReadyValid bundles sourced or sunk by the target
//  // input => sunk by the target
//  private def findRVChannels(dir: Direction): Seq[(String, ReadyValidIO[Data])] =
//    channels.flatMap({ case (prefix, data) => data.elements.toSeq.collect({
//        case (name, rv: ReadyValidIO[_]) if directionOf(rv.valid) == dir => s"${prefix}_${name}" -> rv
//      })
//  })
//
//  lazy val readyValidOutputs = findRVChannels(Direction.Output)
//  lazy val readyValidInputs = findRVChannels(Direction.Input)
}

