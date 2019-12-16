// See LICENSE for license details.

package midas
package core


import midas.widgets.BridgeIOAnnotation
import midas.passes.fame
import midas.passes.fame.{FAMEChannelConnectionAnnotation, DecoupledForwardChannel}
import midas.core.SimUtils._

// from rocketchip
import freechips.rocketchip.config.{Parameters, Field}

import chisel3._
import chisel3.util._
import chisel3.experimental.{Direction, ChiselAnnotation, annotate}
import chisel3.experimental.DataMirror.directionOf
import firrtl.annotations.{SingleTargetAnnotation, ReferenceTarget}

import scala.collection.immutable.ListMap
import scala.collection.mutable.{ArrayBuffer}

case object ChannelLen extends Field[Int]
case object ChannelWidth extends Field[Int]
case object SimWrapperKey extends Field[SimWrapperConfig]

trait HasSimWrapperParams {
  implicit val p: Parameters
  implicit val channelWidth = p(ChannelWidth)
  val traceMaxLen = p(strober.core.TraceMaxLen)
  val daisyWidth = p(strober.core.DaisyWidth)
  val sramChainNum = p(strober.core.SRAMChainNum)
}

private[midas] case class TargetBoxAnnotation(target: ReferenceTarget) extends SingleTargetAnnotation[ReferenceTarget] {
  def duplicate(rt: ReferenceTarget): TargetBoxAnnotation = TargetBoxAnnotation(rt)
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
    case ch @ FAMEChannelConnectionAnnotation(_,fame.PipeChannel(_),Some(srcs),_) => ch -> regenWireType(srcs)
    case ch @ FAMEChannelConnectionAnnotation(_,fame.PipeChannel(_),_,Some(sinks)) => ch -> regenWireType(sinks)
  }).toMap

  val wireElements = ArrayBuffer[(String, ReadyValidIO[Data])]()


  val wirePortMap: Map[String, WirePortTuple] = chAnnos.collect({
    case ch @ FAMEChannelConnectionAnnotation(globalName, fame.PipeChannel(_),sources,sinks) => {
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
      (globalName -> WirePortTuple(sourceP, sinkP))
    }
  }).toMap

  // Looks up a  channel based on a channel name
  val wireOutputPortMap = wirePortMap.collect({
    case (name, portTuple) if portTuple.isOutput => name -> portTuple.source.get
  })

  val wireInputPortMap = wirePortMap.collect({
    case (name, portTuple) if portTuple.isInput => name -> portTuple.sink.get
  })


  val rvElements = ArrayBuffer[(String, ReadyValidIO[Data])]()

  // Using a channel's globalName; look up it's associated port tuple
  val rvPortMap: Map[String, TargetRVPortTuple] = chAnnos.collect({
    case ch @ FAMEChannelConnectionAnnotation(globalName, info@DecoupledForwardChannel(_,_,_,_), leafSources, leafSinks) =>
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
      globalName -> TargetRVPortTuple(sourcePortPair, sinkPortPair)
  }).toMap

  // Looks up a  channel based on a channel name
  val rvOutputPortMap = rvPortMap.collect({
    case (name, portTuple) if portTuple.isOutput => name -> portTuple.source.get
  })

  val rvInputPortMap = rvPortMap.collect({
    case (name, portTuple) if portTuple.isInput => name -> portTuple.sink.get
  })

  // Looks up a FCCA based on a global channel name
  val chNameToAnnoMap = chAnnos.map(anno => anno.globalName -> anno)
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
                         val bridgeAnnos: Seq[BridgeIOAnnotation],
                         leafTypeMap: Map[ReferenceTarget, firrtl.ir.Port])
    extends ChannelizedWrapperIO(chAnnos, leafTypeMap) {

  override val elements = ListMap((wireElements ++ rvElements):_*)
  override def cloneType: this.type = new SimWrapperChannels(chAnnos, bridgeAnnos, leafTypeMap).asInstanceOf[this.type]
}

case class SimWrapperConfig(chAnnos: Seq[FAMEChannelConnectionAnnotation],
                         bridgeAnnos: Seq[BridgeIOAnnotation],
                         leafTypeMap: Map[ReferenceTarget, firrtl.ir.Port])

class SimWrapper(config: SimWrapperConfig)(implicit val p: Parameters) extends MultiIOModule with HasSimWrapperParams {

  outer =>
  val SimWrapperConfig(chAnnos, bridgeAnnos, leafTypeMap) = config

  // Remove all FCAs that are loopback channels. All non-loopback FCAs connect
  // to bridges and will be presented in the SimWrapper's IO
  val bridgeChAnnos = chAnnos.collect({
    case fca @ FAMEChannelConnectionAnnotation(_,_,_,None) => fca
    case fca @ FAMEChannelConnectionAnnotation(_,_,None,_) => fca
  })

  val channelPorts = IO(new SimWrapperChannels(bridgeChAnnos, bridgeAnnos, leafTypeMap))
  val hostReset = IO(Input(Bool()))
  val target = Module(new TargetBox(chAnnos, leafTypeMap))

  // Indicates SimulationMapping which module we want to replace with the simulator
  annotate(new ChiselAnnotation { def toFirrtl =
    TargetBoxAnnotation(outer.toNamed.toTarget.ref(target.instanceName))
  })

  target.io.hostReset := reset.toBool && hostReset
  target.io.clock := clock
  import chisel3.ExplicitCompileOptions.NotStrict // FIXME

  def getPipeChannelType(chAnno: FAMEChannelConnectionAnnotation): ChLeafType = {
    target.io.wireTypeMap(chAnno)
  }

  def genPipeChannel(chAnno: FAMEChannelConnectionAnnotation, latency: Int = 1): PipeChannel[ChLeafType] = {
    require(chAnno.sources == None || chAnno.sources.get.size == 1, "Can't aggregate wire-type channels yet")
    require(chAnno.sinks   == None || chAnno.sinks  .get.size == 1, "Can't aggregate wire-type channels yet")

    val channel = Module(new PipeChannel(getPipeChannelType(chAnno), latency))
    channel suggestName s"PipeChannel_${chAnno.globalName}"

    val portTuple = target.io.wirePortMap(chAnno.globalName)
    portTuple.source match {
      case Some(srcP) => channel.io.in <> srcP
      case None => channel.io.in <> channelPorts.elements(s"${chAnno.globalName}_sink")
    }

    portTuple.sink match {
      case Some(sinkP) => sinkP <> channel.io.out
      case None => channelPorts.elements(s"${chAnno.globalName}_source") <> channel.io.out
    }

    channel.io.trace.ready := DontCare
    channel.io.traceLen := DontCare
    channel
  }

  // Helper functions to attach legacy SimReadyValidIO to true, dual-channel implementations of target ready-valid
  def bindRVChannelEnq[T <: Data](enq: SimReadyValidIO[T], port: TargetRVPortType): Unit = {
    val (fwdPort, revPort) = port
    enq.fwd.hValid   := fwdPort.valid
    enq.target.valid := fwdPort.bits.valid
    enq.target.bits  := fwdPort.bits.bits  // Yeah, i know
    fwdPort.ready := enq.fwd.hReady

    // Connect up the target-ready token channel
    revPort.valid := enq.rev.hValid
    revPort.bits  := enq.target.ready
    enq.rev.hReady := revPort.ready
  }

  def bindRVChannelDeq[T <: Data](deq: SimReadyValidIO[T], port: TargetRVPortType): Unit = {
    val (fwdPort, revPort) = port
    deq.fwd.hReady := fwdPort.ready
    fwdPort.valid      := deq.fwd.hValid
    fwdPort.bits.valid := deq.target.valid
    fwdPort.bits.bits  := deq.target.bits

    // Connect up the target-ready token channel
    deq.rev.hValid   := revPort.valid
    deq.target.ready := revPort.bits
    revPort.ready := deq.rev.hReady
  }


  def getReadyValidChannelType(chAnno: FAMEChannelConnectionAnnotation): Data = {
    target.io.payloadTypeMap(chAnno)
  }

  def genReadyValidChannel(chAnno: FAMEChannelConnectionAnnotation): ReadyValidChannel[Data] = {
      val chName = chAnno.globalName
      val strippedName = chName.stripSuffix("_fwd")
      // Determine which bridge this channel belongs to by looking it up with the valid
      //val bridgeClockRatio = io.bridges.find(_(rvInterface.valid)) match {
      //  case Some(bridge) => bridge.clockRatio
      //  case None => UnityClockRatio
      //}
      val bridgeClockRatio = UnityClockRatio // TODO: FIXME
      // A channel is considered "flipped" if it's sunk by the tranformed RTL (sourced by an bridge)
      val channel = Module(new ReadyValidChannel(getReadyValidChannelType(chAnno).cloneType))

      channel.suggestName(s"ReadyValidChannel_$strippedName")

      val enqPortPair = (chAnno.sources match {
        case Some(_) => target.io.rvOutputPortMap(chName)
        case None => channelPorts.rvInputPortMap(chName)
      })
      bindRVChannelEnq(channel.io.enq, enqPortPair)

      val deqPortPair = (chAnno.sinks match {
        case Some(_) => target.io.rvInputPortMap(chName)
        case None => channelPorts.rvOutputPortMap(chName)
      })
      bindRVChannelDeq(channel.io.deq, deqPortPair)

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
  chAnnos.collect({
    case ch @ FAMEChannelConnectionAnnotation(name, fame.PipeChannel(latency),_,_)  => genPipeChannel(ch, latency)
  })
}
