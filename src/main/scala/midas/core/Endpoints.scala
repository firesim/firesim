package midas
package core

// from rocketchip
import junctions.{NastiIO, NastiKey}
import uncore.axi4.AXI4Bundle
import config.Parameters

import chisel3._
import chisel3.util._
import widgets._
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
  final def add(name: String, channel: Data) {
    val (ins, outs) = SimUtils.parsePorts(channel)
    wires ++= (ins ++ outs).unzip._1
    channels += (name -> channel.asInstanceOf[Record])
  }
}

abstract class SimMemIO extends Endpoint {
  def widget(p: Parameters) = { 
    val param = p alterPartial ({ case NastiKey => p(MemNastiKey) })
    (p(MemModelKey): @unchecked) match {
      case Some(modelGen) => modelGen(param)
      case None => new NastiWidget()(param)
    }
  }
}
 
class SimNastiMemIO extends SimMemIO {
  def matchType(data: Data) = data match {
    case channel: NastiIO => channel.w.valid.dir == OUTPUT
    case _ => false
  }
}

class SimAXI4MemIO extends SimMemIO {
  def matchType(data: Data) = data match {
    case channel: AXI4Bundle => channel.w.valid.dir == OUTPUT
    case _ => false
  }
}

case class EndpointMap(endpoints: Seq[Endpoint]) {
  def get(data: Data) = endpoints find (_ matchType data)
  def ++(x: EndpointMap) = EndpointMap(endpoints ++ x.endpoints) 
}
