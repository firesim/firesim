// See LICENSE for license details.

package midas
package core

import scala.collection.immutable.ListMap
import scala.collection.mutable.ArrayBuffer

// from rocketchip
import junctions.NastiIO
import freechips.rocketchip.amba.axi4.AXI4Bundle
import freechips.rocketchip.config.{Parameters, Field}

import chisel3._
import chisel3.util._
import chisel3.core.{Reset}
import chisel3.experimental.{MultiIOModule, Direction}
import chisel3.experimental.DataMirror.directionOf
import firrtl.transforms.fame
import firrtl.transforms.fame.{FAMEChannelAnnotation, DecoupledForwardChannel}
import firrtl.annotations.{ReferenceTarget}

import scala.collection.immutable.ListMap
import scala.collection.mutable.{ArrayBuffer, HashSet}

object SimUtils {
  type ChLeafType = Bits
  type ChTuple = Tuple2[ChLeafType, String]
  type RVChTuple = Tuple2[ReadyValidIO[Data], String]
  type ParsePortsTuple = (List[ChTuple], List[ChTuple], List[RVChTuple], List[RVChTuple])

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

import SimUtils._

case object ChannelLen extends Field[Int]
case object ChannelWidth extends Field[Int]

trait HasSimWrapperParams {
  implicit val p: Parameters
  implicit val channelWidth = p(ChannelWidth)
  val traceMaxLen = p(strober.core.TraceMaxLen)
  val daisyWidth = p(strober.core.DaisyWidth)
  val sramChainNum = p(strober.core.SRAMChainNum)
}


class SimReadyValidRecord(es: Seq[(String, ReadyValidIO[Data])]) extends Record {
  val elements = ListMap() ++ (es map { case (name, rv) =>
    (directionOf(rv.valid): @unchecked) match {
      case Direction.Input => name -> Flipped(SimReadyValid(rv.bits.cloneType))
      case Direction.Output => name -> SimReadyValid(rv.bits.cloneType)
    }
  })
  def cloneType = new SimReadyValidRecord(es).asInstanceOf[this.type]
}

class ReadyValidTraceRecord(es: Seq[(String, ReadyValidIO[Data])]) extends Record {
  val elements = ListMap() ++ (es map {
    case (name, rv) => name -> ReadyValidTrace(rv.bits.cloneType)
  })
  def cloneType = new ReadyValidTraceRecord(es).asInstanceOf[this.type]
}

// Regenerates the "bits" field of a target ready-valid interface from a list of flattened
// elements that include the "bits_" prefix. This is stripped off.
class PayloadRecord(elms: Seq[(String, Data)]) extends Record {
  override val elements = ListMap((elms map { case (name, data) => name.stripPrefix("bits_") -> data.cloneType }):_*)
  override def cloneType: this.type = new PayloadRecord(elms).asInstanceOf[this.type]
}

class TargetBoxIO(chAnnos: Seq[FAMEChannelAnnotation],
                  leafTypeMap: Map[ReferenceTarget, firrtl.ir.Port]) extends Record {
  val clock = Input(Clock())
  val hostReset = Input(Bool())

  def regenTypesFromField(name: String, tpe: firrtl.ir.Type): Seq[(String, ChLeafType)] = tpe match {
    case firrtl.ir.BundleType(fields) => fields.flatMap(f => regenTypesFromField(prefixWith(name, f.name), f.tpe))
    case firrtl.ir.UIntType(width: firrtl.ir.IntWidth) => Seq(name -> UInt(width.width.toInt.W))
    case firrtl.ir.SIntType(width: firrtl.ir.IntWidth) => Seq(name -> SInt(width.width.toInt.W))
    case _ => throw new RuntimeException(s"Unexpected type in token payload: ${tpe}.")
  }

  def regenTypes(refTargets: Seq[ReferenceTarget]): Seq[(String, ChLeafType)] = {
    val port = leafTypeMap(refTargets.head.copy(component = Seq()))
    val fieldName = refTargets.head.component match {
      case firrtl.annotations.TargetToken.Field(fName) :: Nil => fName
      case firrtl.annotations.TargetToken.Field(fName) :: fields => fName
      case _ => throw new RuntimeException("Expected only a bits field in ReferenceTarget's component.")
    }

    val bitsField = port.tpe match {
      case a: firrtl.ir.BundleType => a.fields.filter(_.name == fieldName).head
      case _ => throw new RuntimeException("ReferenceTargets should point at the channel's bundle.")
    }

    regenTypesFromField("", bitsField.tpe)
  }

  def regenPayloadType(refTargets: Seq[ReferenceTarget]): Data = {
    require(!refTargets.isEmpty)
    // Reject all (String -> Data) pairs not included in the refTargets
    // Use this to remove target valid
    val targetLeafNames = refTargets.map(_.component.reverse.head.value).toSet
    val elements = regenTypes(refTargets).filter({ case (name, f)  => targetLeafNames(name) })
    elements match {
      case (name, field) :: Nil => field // If there's only a single field, just pass out the type
      case elms => new PayloadRecord(elms)
    }
  }

  def regenWireType(refTargets: Seq[ReferenceTarget]): ChLeafType = {
    require(refTargets.size == 1, "FIXME: Handle aggregated wires")
    regenTypes(refTargets).head._2
  }

  val payloadTypeMap: Map[FAMEChannelAnnotation, Data] = chAnnos.collect({
    // Target Decoupled Channels need to have their target-valid ReferenceTarget removed
    case ch @ FAMEChannelAnnotation(_,DecoupledForwardChannel(_,Some(vsrc),_,_),Some(srcs),_) =>
      ch -> regenPayloadType(srcs.filterNot(_ == vsrc))
    case ch @ FAMEChannelAnnotation(_,DecoupledForwardChannel(_,_,_,Some(vsink)),_,Some(sinks)) =>
      ch -> regenPayloadType(sinks.filterNot(_ == vsink))
  }).toMap

  val wireTypeMap: Map[FAMEChannelAnnotation, ChLeafType] = chAnnos.collect({
    case ch @ FAMEChannelAnnotation(_,fame.WireChannel,Some(srcs),_) => ch -> regenWireType(srcs)
    case ch @ FAMEChannelAnnotation(_,fame.WireChannel,_,Some(sinks)) => ch -> regenWireType(sinks)
  }).toMap

  val wireElements = ArrayBuffer[(String, ReadyValidIO[Data])]()
  // _1 => Source port, _2 => Sink Port.
  // (Some, None) -> Source channel
  // (None, Some) -> Sink channel
  // (Some, Some) -> Loop back channel -> two interconnected models
  type WirePortTuple = (Option[ReadyValidIO[Data]], Option[ReadyValidIO[Data]])

  val wirePortMap: Map[FAMEChannelAnnotation, WirePortTuple] = chAnnos.collect({
    case ch @ FAMEChannelAnnotation(_, fame.WireChannel,sources,sinks) => {
      val sinkP = sinks.map({ tRefs =>
        val name = tRefs.head.ref.stripSuffix("_bits")
        val port = Flipped(Decoupled(wireTypeMap(ch)))
        wireElements += name -> port
        port
      })
      val sourceP = sources.map({ tRefs =>
        val name = tRefs.head.ref.stripSuffix("_bits")
        val port = Decoupled(wireTypeMap(ch))
        wireElements += name -> port
        port
      })
      (ch -> (sourceP, sinkP))
    }
  }).toMap

  // Tuple of forward port and reverse (backpressure) port
  type TargetRVPortType = (ReadyValidIO[ValidIO[Data]], ReadyValidIO[Bool])
  // A tuple of Options of the above type. _1 => source port _2 => sink port
  // Same principle as the wire channel, now with a more complex port type
  type TargetRVPortTuple = (Option[TargetRVPortType], Option[TargetRVPortType])

  val rvElements = ArrayBuffer[(String, ReadyValidIO[Data])]()

  // Using a chAnno; look up it's associated port tuple
  val rvPortMap: Map[FAMEChannelAnnotation, TargetRVPortTuple] = chAnnos.collect({
    case ch @ FAMEChannelAnnotation(_, info@DecoupledForwardChannel(_,_,_,_), leafSources, leafSinks) =>
      val sourcePortPair = leafSources.map({ tRefs =>
        require(!tRefs.isEmpty, "FIXME: Are empty decoupleds OK?")
        val validTRef: ReferenceTarget = info.validSource.getOrElse(throw new RuntimeException(
          "Target RV port has leaves but no TRef to a validSource"))
        val readyTRef: ReferenceTarget = info.readySink.getOrElse(throw new RuntimeException(
           "Target RV port has leaves but no TRef to a readySink"))

        val fwdName = validTRef.ref
        val fwdPort = Decoupled(Valid(payloadTypeMap(ch)))
        val revName = readyTRef.ref
        val revPort = Flipped(Decoupled(Bool()))
        rvElements ++= Seq((fwdName -> fwdPort), (revName -> revPort))
        (fwdPort, revPort)
      })

      val sinkPortPair = leafSinks.map({ tRefs =>
        require(!tRefs.isEmpty, "FIXME: Are empty decoupleds OK?")
        val validTRef: ReferenceTarget = info.validSink.getOrElse(throw new RuntimeException(
          "Target RV port has payload sinks but no TRef to a validSink"))
        val readyTRef: ReferenceTarget = info.readySource.getOrElse(throw new RuntimeException(
          "Target RV port has payload sinks but no TRef to a readySource"))

        val fwdName = validTRef.ref
        val fwdPort = Flipped(Decoupled(Valid(payloadTypeMap(ch))))
        val revName = readyTRef.ref
        val revPort = Decoupled(Bool())
        rvElements ++= Seq((fwdName -> fwdPort), (revName -> revPort))
        (fwdPort, revPort)
      })
      ch -> (sourcePortPair, sinkPortPair)
  }).toMap

  override val elements = ListMap((wireElements ++ rvElements):_*) ++
    // Untokenized ports
    ListMap("clock" -> clock, "hostReset" -> hostReset)

  override def cloneType: this.type = new TargetBoxIO(chAnnos, leafTypeMap).asInstanceOf[this.type]
}

class TargetBox(chAnnos: Seq[FAMEChannelAnnotation],
               leafTypeMap: Map[ReferenceTarget, firrtl.ir.Port]) extends BlackBox {
  val io = IO(new TargetBoxIO(chAnnos, leafTypeMap))
}

class SimWrapperIO(val targetIo: Seq[Data],
                   val chAnnos: Seq[FAMEChannelAnnotation])(implicit val p: Parameters) extends Record {
  //import chisel3.core.ExplicitCompileOptions.NotStrict // FIXME

  // Generate (ChLeafType -> flatName: String) tuples that identify all of the token
  // channels on the target
  val channelizedPorts = targetIo.map({ port => port -> SimUtils.parsePorts(port, port.instanceName, false) })
  val portToChannelsMap = ListMap(channelizedPorts:_*)

  // Need a beter name for this
  val wireInputs:  Seq[ChTuple] = channelizedPorts flatMap { case (port, (is, _, _, _)) => is }
  val wireOutputs: Seq[ChTuple] = channelizedPorts flatMap { case (port, (_, os, _, _)) => os }

  // This gives a means to look up the the name of the channel that sinks or sources
  // a particular element using the original data field
  def generateChannelIO(channelDefns: Seq[ChTuple]) = channelDefns map { case (elm, name) =>
    if (directionOf(elm) == Direction.Input) {
      (name -> Flipped(Decoupled(elm.cloneType)))
    } else {
      (name -> Decoupled(elm.cloneType))
    }
  }

  val wireInputPorts = generateChannelIO(wireInputs)
  val wireOutputPorts = generateChannelIO(wireOutputs)
  // Look up the token port using the name of the channel
  val wirePortMap: ListMap[String, DecoupledIO[ChLeafType]] = ListMap((wireInputPorts ++ wireOutputPorts):_*)
  // Look up the channel name using the ChiselType associated with it
  val wireTypeMap: ListMap[ChLeafType, String] = ListMap((wireInputs ++ wireOutputs):_*)
  def wireName2port(name: String): DecoupledIO[ChLeafType] = wirePortMap(name)
  // Look up a wire port using the element from the target's port list
  def apply(chiselType: ChLeafType): DecoupledIO[ChLeafType] = wirePortMap(wireTypeMap(chiselType))

  // Ready-valid channels
  def generateRVChannelIO(channelDefns: Seq[RVChTuple]) = channelDefns.map({ case (rv, name) =>
    if (directionOf(rv.valid) == Direction.Input) {
      (name -> Flipped(SimReadyValid(rv.bits.cloneType)))
    } else {
      (name -> SimReadyValid(rv.bits.cloneType))
    }
  })

  val readyValidInputs:  Seq[RVChTuple] = channelizedPorts flatMap { case (port, (_, _, rvis, _)) => rvis }
  val readyValidOutputs: Seq[RVChTuple] = channelizedPorts flatMap { case (port, (_, _, _, rvos)) => rvos }

  val readyValidInputPorts : Seq[(String, SimReadyValidIO[Data])] = generateRVChannelIO(readyValidInputs)
  val readyValidOutputPorts: Seq[(String, SimReadyValidIO[Data])] = generateRVChannelIO(readyValidOutputs)
  val readyValidInMap: ListMap[ReadyValidIO[Data], SimReadyValidIO[Data]] = ListMap((readyValidInputs.zip(readyValidInputPorts)).map({
      case (defn, port) => defn._1 -> port._2 }):_*)
  val readyValidOutMap : ListMap[ReadyValidIO[Data], SimReadyValidIO[Data]] = ListMap((readyValidOutputs.zip(readyValidOutputPorts)).map({
      case (defn, port) => defn._1 -> port._2 }):_*)
  val readyValidMap: ListMap[ReadyValidIO[Data], SimReadyValidIO[Data]] = readyValidInMap ++ readyValidOutMap
  // Look up a channel port using the target's RV port
  def apply(rv: ReadyValidIO[Data]): SimReadyValidIO[Data] = readyValidMap(rv)
  val readyValidPortMap = ListMap((readyValidInputPorts ++ readyValidOutputPorts):_*)

  ///*** Endpoints ***/
  val endpointMap = p(EndpointKey)
  val endpoints = endpointMap.endpoints
  private def findEndpoint(name: String, data: Data) {
    endpointMap get data match {
      case Some(endpoint) =>
        endpoint add (name, data)
      case None => data match {
        case b: Record => b.elements foreach {
          case (n, e) => findEndpoint(s"${name}_${n}", e)
        }
        case v: Vec[_] => v.zipWithIndex foreach {
          case (e, i) => findEndpoint(s"${name}_${i}", e)
        }
        case _ =>
      }
    }
  }
  targetIo.foreach({ port => findEndpoint(port.instanceName, port)})

  /*** Wire Channels ***/
  val endpointWires = (endpoints flatMap (ep => (0 until ep.size) flatMap { i =>
    val (prefix, data) = ep(i)
    data.elements.toSeq flatMap {
      case (name, rv: ReadyValidIO[_]) => Nil
      case (name, wires) =>
        val (ins, outs, _, _) = SimUtils.parsePorts(wires)
        (ins ++ outs).unzip._1
    }
  })).toSet

  /*** Wire Channels ***/
  val endpointRVs = (endpoints flatMap (ep => (0 until ep.size) flatMap { i =>
      val (prefix, data) = ep(i)
      val (_, _, rvis, rvos) = SimUtils.parsePorts(data)
      (rvis ++ rvos).unzip._1
    }
  )).toSet

  // Inputs that are not target decoupled
  val pokedInputs = wireInputs filterNot (x => endpointWires(x._1))
  val peekedOutputs = wireOutputs filterNot (x => endpointWires(x._1))
  val pokedReadyValidInputs = readyValidInputs filterNot (x => endpointRVs(x._1))
  val peekedReadyValidOutputs = readyValidOutputs filterNot (x => endpointRVs(x._1))

  override val elements = wirePortMap ++
    ListMap((readyValidInputPorts ++ readyValidOutputPorts):_*)

  override def cloneType: this.type =
    new SimWrapperIO(targetIo, chAnnos).asInstanceOf[this.type]
}

class SimBox(simIo: SimWrapperIO) (implicit val p: Parameters) extends BlackBox with HasSimWrapperParams {
  val io = IO(new Bundle {
    val channelPorts = simIo.cloneType
    val reset = Input(Bool())
    val hostReset = Input(Bool())
    val clock = Input(Clock())
  })
}

class SimWrapper(targetIo: Seq[Data],
                 chAnnos: Seq[FAMEChannelAnnotation],
                 leafTypeMap: Map[ReferenceTarget, firrtl.ir.Port])(implicit val p: Parameters) extends MultiIOModule with HasSimWrapperParams {
  val channelPorts = IO(new SimWrapperIO(targetIo, chAnnos))
  val hostReset = IO(Input(Bool()))
  val target = Module(new TargetBox(chAnnos, leafTypeMap))
  target.io.hostReset := reset.toBool && hostReset
  target.io.clock := clock
  import chisel3.core.ExplicitCompileOptions.NotStrict // FIXME

  def getWireChannelType(chAnno: FAMEChannelAnnotation): ChLeafType = {
    target.io.wireTypeMap(chAnno)
  }

  def genWireChannel(chAnno: FAMEChannelAnnotation): WireChannel[ChLeafType] = {
    require(chAnno.sources == None || chAnno.sources.get.size == 1, "Can't aggregate wire-type channels yet")
    require(chAnno.sinks   == None || chAnno.sinks  .get.size == 1, "Can't aggregate wire-type channels yet")
    require(chAnno.sinks   == None || chAnno.sources == None, "Can't handle excised channels yet")

    val channel = Module(new WireChannel(getWireChannelType(chAnno)))
    channel suggestName s"WireChannel_${chAnno.name}"

    val (srcPort, sinkPort) = target.io.wirePortMap(chAnno)
    srcPort match {
      case Some(srcP) => channel.io.in <> srcP
      case None => channel.io.in <> channelPorts.elements(chAnno.name)
    }

    sinkPort match {
      case Some(sinkP) => sinkP <> channel.io.out
      case None => channelPorts.elements(chAnno.name) <> channel.io.out
    }

    channel.io.trace.ready := DontCare
    channel.io.traceLen := DontCare
    channel
  }

  def bindOutputRVChannel[T <: Data](enq: SimReadyValidIO[T], chAnno: FAMEChannelAnnotation) {
    val (fwdPort, revPort) = target.io.rvPortMap(chAnno)._1.get // Get the source-port pair
    enq.fwd.hValid   := fwdPort.valid
    enq.target.valid := fwdPort.bits.valid
    enq.target.bits  := fwdPort.bits.bits  // Yeah, i know
    fwdPort.ready := enq.fwd.hReady

    // Connect up the target-ready token channel
    revPort.valid := enq.rev.hValid
    revPort.bits  := enq.target.ready
    enq.rev.hReady := revPort.ready
  }

  def bindInputRVChannel[T <: Data](deq: SimReadyValidIO[T], chAnno: FAMEChannelAnnotation) {
    val (fwdPort, revPort) = target.io.rvPortMap(chAnno)._2.get // Get the sink-port pair
    deq.fwd.hReady := fwdPort.ready
    fwdPort.valid      := deq.fwd.hValid
    fwdPort.bits.valid := deq.target.valid
    fwdPort.bits.bits  := deq.target.bits

    // Connect up the target-ready token channel
    deq.rev.hValid   := revPort.valid
    deq.target.ready := revPort.bits
    revPort.ready := deq.rev.hReady
  }

  def getReadyValidChannelType(chAnno: FAMEChannelAnnotation): Data = {
    target.io.payloadTypeMap(chAnno)
  }

  def genReadyValidChannel(chAnno: FAMEChannelAnnotation): ReadyValidChannel[Data] = {
    val strippedName = chAnno.name.stripSuffix("_fwd")
    require(chAnno.sinks   == None || chAnno.sources == None, "Can't handle excised channels yet")
      // Determine which endpoint this channel belongs to by looking it up with the valid
      //val endpointClockRatio = io.endpoints.find(_(rvInterface.valid)) match {
      //  case Some(endpoint) => endpoint.clockRatio
      //  case None => UnityClockRatio
      //}
      val endpointClockRatio = UnityClockRatio // TODO: FIXME
      // A channel is considered "flipped" if it's sunk by the tranformed RTL (sourced by an endpoint)
      val channel = Module(new ReadyValidChannel(getReadyValidChannelType(chAnno).cloneType))

      channel.suggestName(s"ReadyValidChannel_$strippedName")

      chAnno.sources match {
        case Some(_) => bindOutputRVChannel(channel.io.enq, chAnno)
        case None => channel.io.enq <> channelPorts.readyValidPortMap(strippedName)
      }

      chAnno.sinks match {
        case Some(_) => bindInputRVChannel(channel.io.deq, chAnno)
        case None => channelPorts.readyValidPortMap(strippedName) <> channel.io.deq
      }

      channel.io.trace := DontCare
      channel.io.traceLen := DontCare
      channel.io.targetReset.bits := false.B
      channel.io.targetReset.valid := true.B
      channel
  }

  // Iterate through the channel annotations to generate channels
  chAnnos.foreach({ _ match {
    case ch @ FAMEChannelAnnotation(_,firrtl.transforms.fame.WireChannel,_,_) => genWireChannel(ch)
    case ch @ FAMEChannelAnnotation(_,firrtl.transforms.fame.DecoupledForwardChannel(_,_,_,_),_,_) => genReadyValidChannel(ch)
    case _ => Nil // Drop DecoupledReverseChannel annotations
  }})
}
