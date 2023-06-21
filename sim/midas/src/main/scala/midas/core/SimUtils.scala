// See LICENSE for license details.

package midas.core

import chisel3._
import chisel3.util._
import chisel3.experimental.{Direction}
import chisel3.experimental.DataMirror.directionOf

import firrtl.annotations.ReferenceTarget

import scala.collection.mutable.{ArrayBuffer}
import scala.collection.immutable.{ListMap}

// A collection of useful types and methods for moving between target and host-land interfaces
object SimUtils {
  type ChTuple = Tuple2[Bits, String]
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
      case b: Bits => (directionOf(b): @unchecked) match {
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

  // Returns reference to all clocks
  def findClocks(field: Data): Seq[Clock] = field match {
    case c: Clock => Seq(c)
    case b: Record => b.elements.flatMap({ case (_, field) => findClocks(field) }).toSeq
    case v: Vec[_] => v.flatMap(findClocks)
    case o => Seq()
  }

  // !FIXME! FCCA renamer can't handle flattening of an aggregate target; so do it manually
  def lowerAggregateIntoLeafTargets(bits: Data): Seq[ReferenceTarget] = {
    val (ins, outs, _, _) = SimUtils.parsePorts(bits)
    require (ins.isEmpty || outs.isEmpty, "Aggregate should be uni-directional")
    (ins ++ outs).map({ case (leafField, _) => leafField.toNamed.toTarget })
  }

  // Simple wrapper for nested bundles.
  private class BundleRecord(elms: Seq[(String, Data)]) extends Record {
    override val elements = ListMap((elms.map { case (name, data) => name -> data.cloneType }):_*)
    override def toString: String = s"{${elements.map({case (name, data) => s"${name}: ${data}"}).mkString(", ")}}"
  }

  /**
    * Construct a type for a channel carrying the wires referenced by the list of targets.
    *
    * The reference targets denote the set of fields of a port which should be included in a channel.
    * A type is built including only those fields, which should be unidirectional. This is now used
    * to remove the valid field of a ready-valid channel payload. The "bits_" suffix of fields is
    * removed to improve readability, but this should be changed in the future.
    *
    * @param portTypeMap Mapping from port references to their types.
    * @param refTargets List of fields in the channel. Must all point to sub-fields or sub-indices of the port.
    */
  def buildChannelType(portTypeMap: Map[ReferenceTarget, firrtl.ir.Port], refTargets: Seq[ReferenceTarget]): Data = {
    require(refTargets.nonEmpty)

    // Ensure all reference targets point to the same port and find it.
    val portTarget = refTargets.head.copy(component = Seq())
    require(refTargets.forall(_.copy(component = Seq()) == portTarget))
    val portType = portTypeMap(portTarget).tpe

    // Find the type of the field of the bundle. Since an annotation cannot automatically be
    // adjusted to target the individual fields of the bundle after type lowering, all fields
    // are eagerly enumerated early on. Identify the type of the root bundle here.
    val fieldName = refTargets.head.component.headOption match {
      case Some(firrtl.annotations.TargetToken.Field(fName)) => fName
      case _ => throw new RuntimeException("Expected only a bits field in ReferenceTarget's component.")
    }
    val bitsType = portType match {
      case a: firrtl.ir.BundleType => a.fields.filter(_.name == fieldName).head.tpe
      case _ => throw new RuntimeException("ReferenceTargets should point at the channel's bundle.")
    }
    // Reject all nested fields not referenced by a target. This is used to remove the
    // valid field of decoupled ready-valid channels (or any other fields pointing in 
    // the other direction) from channelized bundles.
    val targetLeafNames = refTargets.map(_.component.tail).toSet

    // Recursively map the FIRRTL type to chisel types representing the payload.
    // This method traverses the type recursively, matching its fields with the ones 
    // referenced by the annotation. A bundle type is built, excluding all filtered
    // fields and flattening single-element structures. Additionally, "bits_" prefixes
    // are dropped to generate names expected by ready-valid channels.
    def loop(token: Seq[firrtl.annotations.TargetToken], tpe: firrtl.ir.Type): Option[Data] = tpe match {
      case firrtl.ir.UIntType(width: firrtl.ir.IntWidth) =>
        if (targetLeafNames.contains(token)) Some(UInt(width.width.toInt.W)) else None
      case firrtl.ir.SIntType(width: firrtl.ir.IntWidth) =>
        if (targetLeafNames.contains(token)) Some(SInt(width.width.toInt.W)) else None
      case firrtl.ir.BundleType(fields) => {
        val nested = fields
          .map(field => {
            val tokenName = token ++ List(firrtl.annotations.TargetToken.Field(field.name))
            loop(tokenName, field.tpe).map { value =>
              tokenName match {
                case firrtl.annotations.TargetToken.Field(name) :: Nil => field.name.stripPrefix("bits_") -> value
                case _ => field.name -> value
              }
            }
          })
          .collect({ case Some(field) => field })

        nested match {
          case Nil => None
          case (name, value) :: Nil => Some(value)
          case fields => Some(new BundleRecord(fields))
        }
      }
    }

    loop(List(), bitsType).get
  }
}
