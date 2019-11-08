// See LICENSE for license details.

package midas.core

import chisel3._
import chisel3.util._
import chisel3.experimental.{Direction}
import chisel3.experimental.DataMirror.directionOf

import scala.collection.mutable.{ArrayBuffer}

// A collection of useful types and methods for moving between target and host-land interfaces
object SimUtils {
  type ChLeafType = Bits
  type ChTuple = Tuple2[ChLeafType, String]
  type RVChTuple = Tuple2[ReadyValidIO[Data], String]
  type ParsePortsTuple = (List[ChTuple], List[ChTuple], List[RVChTuple], List[RVChTuple])

  // (Some, None) -> Source channel
  // (None, Some) -> Sink channel
  // (Some, Some) -> Loop back channel -> two interconnected models
  trait PortTuple[T <: Any] {
    def source: Option[T]
    def sink:   Option[T]
    def isOutput(): Boolean = sink == None
    def isInput(): Boolean = source == None
    def isLoopback(): Boolean = source != None && sink != None
  }

  case class WirePortTuple(source: Option[ReadyValidIO[Data]], sink: Option[ReadyValidIO[Data]]) 
      extends PortTuple[ReadyValidIO[Data]]{
    require(source != None || sink != None)
  }
  // Tuple of forward port and reverse (backpressure) port
  type TargetRVPortType = (ReadyValidIO[ValidIO[Data]], ReadyValidIO[Bool])
  // A tuple of Options of the above type. _1 => source port _2 => sink port
  // Same principle as the wire channel, now with a more complex port type
  case class TargetRVPortTuple(source: Option[TargetRVPortType], sink: Option[TargetRVPortType])
      extends PortTuple[TargetRVPortType]{
    require(source != None || sink != None)
  }

  def rvChannelNamePair(chName: String): (String, String) = (chName + "_fwd", chName + "_rev")
  def rvChannelNamePair(tuple: RVChTuple): (String, String) = rvChannelNamePair(tuple._2)

  def prefixWith(prefix: String, base: Any): String =
    if (prefix != "")  s"${prefix}_${base}" else base.toString

  // Returns a list of input and output elements, with their flattened names
  def parsePorts(io: Seq[(String, Data)], alsoFlattenRVPorts: Boolean): ParsePortsTuple = {
    val inputs = ArrayBuffer[ChTuple]()
    val outputs = ArrayBuffer[ChTuple]()
    val rvInputs = ArrayBuffer[RVChTuple]()
    val rvOutputs = ArrayBuffer[RVChTuple]()

    def loop(name: String, data: Data): Unit = data match {
      case c: Clock => // skip
      case rv: ReadyValidIO[_] => (directionOf(rv.valid): @unchecked) match {
          case Direction.Input =>  rvInputs  += (rv -> name)
          case Direction.Output => rvOutputs += (rv -> name)
        }
        if (alsoFlattenRVPorts) rv.elements foreach {case (n, e) => loop(prefixWith(name, n), e)}
      case b: Record =>
        b.elements foreach {case (n, e) => loop(prefixWith(name, n), e)}
      case v: Vec[_] =>
        v.zipWithIndex foreach {case (e, i) => loop(prefixWith(name, i), e)}
      case b: ChLeafType => (directionOf(b): @unchecked) match {
        case Direction.Input => inputs += (b -> name)
        case Direction.Output => outputs += (b -> name)

      }
    }
    io.foreach({ case (name, port) => loop(name, port)})
    (inputs.toList, outputs.toList, rvInputs.toList, rvOutputs.toList)
  }

  def parsePorts(io: Data, prefix: String = "", alsoFlattenRVPorts: Boolean = true): ParsePortsTuple =
    parsePorts(Seq(prefix -> io), alsoFlattenRVPorts)

  def parsePortsSeq(io: Seq[(String, Data)], alsoFlattenRVPorts: Boolean = true): ParsePortsTuple =
    parsePorts(io, alsoFlattenRVPorts)

}
