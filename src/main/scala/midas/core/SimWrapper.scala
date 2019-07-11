// See LICENSE for license details.

package midas
package core


import midas.widgets.EndpointIOAnnotation
import midas.passes.fame
import midas.passes.fame.{FAMEChannelConnectionAnnotation, DecoupledForwardChannel}
// from rocketchip
import junctions.NastiIO
import freechips.rocketchip.amba.axi4.AXI4Bundle
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.util.{DecoupledHelper}

import chisel3._
import chisel3.util._
import chisel3.core.{Reset}
import chisel3.experimental.{MultiIOModule, Direction}
import chisel3.experimental.DataMirror.directionOf
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

abstract class ChannelizedWrapperIO(chAnnos: Seq[FAMEChannelConnectionAnnotation],
                           leafTypeMap: Map[ReferenceTarget, firrtl.ir.Port]) extends Record {

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

  val payloadTypeMap: Map[FAMEChannelConnectionAnnotation, Data] = chAnnos.collect({
    // Target Decoupled Channels need to have their target-valid ReferenceTarget removed
    case ch @ FAMEChannelConnectionAnnotation(_,DecoupledForwardChannel(_,Some(vsrc),_,_),Some(srcs),_) =>
      ch -> regenPayloadType(srcs.filterNot(_ == vsrc))
    case ch @ FAMEChannelConnectionAnnotation(_,DecoupledForwardChannel(_,_,_,Some(vsink)),_,Some(sinks)) =>
      ch -> regenPayloadType(sinks.filterNot(_ == vsink))
  }).toMap

  val wireTypeMap: Map[FAMEChannelConnectionAnnotation, ChLeafType] = chAnnos.collect({
    case ch @ FAMEChannelConnectionAnnotation(_,fame.WireChannel,Some(srcs),_) => ch -> regenWireType(srcs)
    case ch @ FAMEChannelConnectionAnnotation(_,fame.WireChannel,_,Some(sinks)) => ch -> regenWireType(sinks)
  }).toMap

  val wireElements = ArrayBuffer[(String, ReadyValidIO[Data])]()
  // _1 => Source port, _2 => Sink Port.
  // (Some, None) -> Source channel
  // (None, Some) -> Sink channel
  // (Some, Some) -> Loop back channel -> two interconnected models
  type WirePortTuple = (Option[ReadyValidIO[Data]], Option[ReadyValidIO[Data]])

  val wirePortMap: Map[FAMEChannelConnectionAnnotation, WirePortTuple] = chAnnos.collect({
    case ch @ FAMEChannelConnectionAnnotation(_, fame.WireChannel,sources,sinks) => {
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
  val rvPortMap: Map[FAMEChannelConnectionAnnotation, TargetRVPortTuple] = chAnnos.collect({
    case ch @ FAMEChannelConnectionAnnotation(_, info@DecoupledForwardChannel(_,_,_,_), leafSources, leafSinks) =>
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

  // Helper functions to attach legacy SimReadyValidIO to true, dual-channel implementations of target ready-valid
  def bindRVChannelEnq[T <: Data](enq: SimReadyValidIO[T], chAnno: FAMEChannelConnectionAnnotation) {
    val (fwdPort, revPort) = rvPortMap(chAnno)._1.get // Get the source-port pair
    enq.fwd.hValid   := fwdPort.valid
    enq.target.valid := fwdPort.bits.valid
    enq.target.bits  := fwdPort.bits.bits  // Yeah, i know
    fwdPort.ready := enq.fwd.hReady

    // Connect up the target-ready token channel
    revPort.valid := enq.rev.hValid
    revPort.bits  := enq.target.ready
    enq.rev.hReady := revPort.ready
  }

  def bindRVChannelDeq[T <: Data](deq: SimReadyValidIO[T], chAnno: FAMEChannelConnectionAnnotation) {
    val (fwdPort, revPort) = rvPortMap(chAnno)._2.get // Get the sink-port pair
    deq.fwd.hReady := fwdPort.ready
    fwdPort.valid      := deq.fwd.hValid
    fwdPort.bits.valid := deq.target.valid
    fwdPort.bits.bits  := deq.target.bits

    // Connect up the target-ready token channel
    deq.rev.hValid   := revPort.valid
    deq.target.ready := revPort.bits
    revPort.ready := deq.rev.hReady
  }
}

class TargetBoxIO(val chAnnos: Seq[FAMEChannelConnectionAnnotation],
                   leafTypeMap: Map[ReferenceTarget, firrtl.ir.Port])
                  extends ChannelizedWrapperIO(chAnnos, leafTypeMap) {

  val clock = Input(Clock())
  val hostReset = Input(Bool())
  override val elements = ListMap((wireElements ++ rvElements):_*) ++
    // Untokenized ports
    ListMap("clock" -> clock, "hostReset" -> hostReset)
  override def cloneType: this.type = new TargetBoxIO(chAnnos, leafTypeMap).asInstanceOf[this.type]
}

class TargetBox(chAnnos: Seq[FAMEChannelConnectionAnnotation],
               leafTypeMap: Map[ReferenceTarget, firrtl.ir.Port]) extends BlackBox {
  val io = IO(new TargetBoxIO(chAnnos, leafTypeMap))
}

class SimWrapperChannels(val chAnnos: Seq[FAMEChannelConnectionAnnotation],
                         val endpointAnnos: Seq[EndpointIOAnnotation],
                         leafTypeMap: Map[ReferenceTarget, firrtl.ir.Port])
    extends ChannelizedWrapperIO(chAnnos, leafTypeMap) {

  override val elements = ListMap((wireElements ++ rvElements):_*)
  override def cloneType: this.type = new SimWrapperChannels(chAnnos, endpointAnnos, leafTypeMap).asInstanceOf[this.type]
}


  /////*** Endpoints ***/
  //val endpointMap = p(EndpointKey)
  //val endpoints = endpointMap.endpoints
  //private def findEndpoint(name: String, data: Data) {
  //  endpointMap get data match {
  //    case Some(endpoint) =>
  //      endpoint add (name, data)
  //    case None => data match {
  //      case b: Record => b.elements foreach {
  //        case (n, e) => findEndpoint(s"${name}_${n}", e)
  //      }
  //      case v: Vec[_] => v.zipWithIndex foreach {
  //        case (e, i) => findEndpoint(s"${name}_${i}", e)
  //      }
  //      case _ =>
  //    }
  //  }
  //}
  //targetIo.foreach({ case (name, port) => findEndpoint(name, port)})

  ///*** Wire Channels ***/
  //val endpointWires = (endpoints flatMap (ep => (0 until ep.size) flatMap { i =>
  //  val (prefix, data) = ep(i)
  //  data.elements.toSeq flatMap {
  //    case (name, rv: ReadyValidIO[_]) => Nil
  //    case (name, wires) =>
  //      val (ins, outs, _, _) = SimUtils.parsePorts(wires)
  //      (ins ++ outs).unzip._1
  //  }
  //})).toSet

  ///*** Wire Channels ***/
  //val endpointRVs = (endpoints flatMap (ep => (0 until ep.size) flatMap { i =>
  //    val (prefix, data) = ep(i)
  //    val (_, _, rvis, rvos) = SimUtils.parsePorts(data)
  //    (rvis ++ rvos).unzip._1
  //  }
  //)).toSet

  //// Inputs that are not target decoupled
  //val pokedInputs = wireInputs filterNot (x => endpointWires(x._1))
  //val peekedOutputs = wireOutputs filterNot (x => endpointWires(x._1))
  //val pokedReadyValidInputs = readyValidInputs filterNot (x => endpointRVs(x._1))
  //val peekedReadyValidOutputs = readyValidOutputs filterNot (x => endpointRVs(x._1))

  //override def cloneType: this.type =
  //  new SimWrapperIO(chAnnos, leafTypeMap).asInstanceOf[this.type]

class SimBox(simChannels: SimWrapperChannels) extends BlackBox {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val hostReset = Input(Bool())
    val channelPorts = simChannels.cloneType
  })
}

class SimWrapper(chAnnos: Seq[FAMEChannelConnectionAnnotation],
                 endpointAnnos: Seq[EndpointIOAnnotation],
                 leafTypeMap: Map[ReferenceTarget, firrtl.ir.Port])
                (implicit val p: Parameters) extends MultiIOModule with HasSimWrapperParams {

  // Remove all FCAs that are loopback channels. All non-loopback FCAs connect
  // to endpoints and will be presented in the SimWrapper's IO
  val endpointChAnnos = chAnnos.collect({
    case fca @ FAMEChannelConnectionAnnotation(_,_,_,None) => fca
    case fca @ FAMEChannelConnectionAnnotation(_,_,None,_) => fca
  })

  val channelPorts = IO(new SimWrapperChannels(endpointChAnnos, endpointAnnos, leafTypeMap))
  val hostReset = IO(Input(Bool()))
  val target = Module(new TargetBox(chAnnos, leafTypeMap))

  target.io.hostReset := reset.toBool && hostReset
  target.io.clock := clock
  import chisel3.core.ExplicitCompileOptions.NotStrict // FIXME

  def getWireChannelType(chAnno: FAMEChannelConnectionAnnotation): ChLeafType = {
    target.io.wireTypeMap(chAnno)
  }

  def genWireChannel(chAnno: FAMEChannelConnectionAnnotation, latency: Int = 1): WireChannel[ChLeafType] = {
    require(chAnno.sources == None || chAnno.sources.get.size == 1, "Can't aggregate wire-type channels yet")
    require(chAnno.sinks   == None || chAnno.sinks  .get.size == 1, "Can't aggregate wire-type channels yet")

    val channel = Module(new WireChannel(getWireChannelType(chAnno), latency))
    channel suggestName s"WireChannel_${chAnno.globalName}"

    val (srcPort, sinkPort) = target.io.wirePortMap(chAnno)
    srcPort match {
      case Some(srcP) => channel.io.in <> srcP
      case None => channel.io.in <> channelPorts.elements(s"${chAnno.globalName}_sink")
    }

    sinkPort match {
      case Some(sinkP) => sinkP <> channel.io.out
      case None => channelPorts.elements(s"${chAnno.globalName}_source") <> channel.io.out
    }

    channel.io.trace.ready := DontCare
    channel.io.traceLen := DontCare
    channel
  }


  def getReadyValidChannelType(chAnno: FAMEChannelConnectionAnnotation): Data = {
    target.io.payloadTypeMap(chAnno)
  }

  def genReadyValidChannel(chAnno: FAMEChannelConnectionAnnotation): ReadyValidChannel[Data] = {
    val strippedName = chAnno.globalName.stripSuffix("_fwd")
      // Determine which endpoint this channel belongs to by looking it up with the valid
      //val endpointClockRatio = io.endpoints.find(_(rvInterface.valid)) match {
      //  case Some(endpoint) => endpoint.clockRatio
      //  case None => UnityClockRatio
      //}
      val endpointClockRatio = UnityClockRatio // TODO: FIXME
      // A channel is considered "flipped" if it's sunk by the tranformed RTL (sourced by an endpoint)
      val channel = Module(new ReadyValidChannel(getReadyValidChannelType(chAnno).cloneType))

      channel.suggestName(s"ReadyValidChannel_$strippedName")

      (chAnno.sources match {
        case Some(_) => target.io
        case None => channelPorts
      }).bindRVChannelEnq(channel.io.enq, chAnno)

      (chAnno.sinks match {
        case Some(_) => target.io
        case None => channelPorts
      }).bindRVChannelDeq(channel.io.deq, chAnno)

      channel.io.trace := DontCare
      channel.io.traceLen := DontCare
      channel.io.targetReset.bits := false.B
      channel.io.targetReset.valid := true.B
      channel
  }

  // Generate all ready-valid channels
  val rvChannels = chAnnos.collect({
    case ch @ FAMEChannelConnectionAnnotation(_,fame.DecoupledForwardChannel(_,_,_,_),_,_) => genReadyValidChannel(ch)
  })

  // Generate all wire channels, excluding reset
  // TODO: This longstanding hack needs to be removed by doing channel excision on target-stateful channels
  // the appropriate reset for that channel will then be plumbed out with the rest of the queue.
  val resetChannelName = "PeekPokeEndpoint_reset"
  chAnnos.collect({
    case ch @ FAMEChannelConnectionAnnotation(name, fame.WireChannel,_,_) if name != resetChannelName  => genWireChannel(ch, 0)
  })

  val resetChannel = chAnnos.collectFirst({
    case ch @ FAMEChannelConnectionAnnotation(name, fame.WireChannel,_,_) if name == resetChannelName  => genWireChannel(ch, 0)
  }).get

  //val resetPort = channelPorts.elements(resetChannelName + "_sink")
  //// Fan out targetReset tokens to all target-stateful channels
  //val tResetQueues = rvChannels.map(_ => Module(new Queue(Bool(), 2)))
  //val tResetHelper = DecoupledHelper((
  //  tResetQueues.map(_.io.enq.ready) :+
  //  resetChannel.io.in.ready :+
  //  resetPort.valid):_*)

  //(rvChannels.zip(tResetQueues)).foreach({ case (channel, resetQueue) =>
  //  channel.io.targetReset <> resetQueue.io.deq
  //  resetQueue.io.enq.bits := resetPort.bits
  //  resetQueue.io.enq.valid := tResetHelper.fire
  //})
  //// Override the connections generated in genWireChannel which assume the
  //// reset token is not fanning out
  //resetChannel.io.in.valid := tResetHelper.fire
  //resetPort.ready := tResetHelper.fire(resetPort.valid)
}
