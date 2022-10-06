// See LICENSE for license details.

package midas
package core


import midas.widgets.BridgeIOAnnotation
import midas.passes.fame
import midas.passes.fame.{FAMEChannelConnectionAnnotation, DecoupledForwardChannel, FAMEChannelFanoutAnnotation}
import midas.core.SimUtils._

import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.util.{DecoupledHelper}

import chisel3._
import chisel3.util._
import chisel3.experimental.{Direction, ChiselAnnotation, annotate}
import chisel3.experimental.DataMirror.directionOf
import firrtl.annotations.{Annotation, SingleTargetAnnotation, ReferenceTarget, IsModule}

import scala.collection.immutable.ListMap
import scala.collection.mutable

case object SimWrapperKey extends Field[SimWrapperConfig]

private[midas] case class TargetBoxAnnotation(target: IsModule) extends SingleTargetAnnotation[IsModule] {
  def duplicate(rt: IsModule): TargetBoxAnnotation = TargetBoxAnnotation(rt)
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

// Regenerates the "bits" field of a target ready-valid interface from a list of flattened
// elements that include the "bits_" prefix. This is stripped off.
class PayloadRecord(elms: Seq[(String, Data)]) extends Record {
  override val elements = ListMap((elms map { case (name, data) => name.stripPrefix("bits_") -> data.cloneType }):_*)
  override def cloneType: this.type = new PayloadRecord(elms).asInstanceOf[this.type]
}

/**
  * The metadata required to generate the simulation wrapper.
  *
  * @param annotations Notably [[FAMEChannelConnectionAnnotation]],
  * [[FAMEChannelFanoutAnnotation]], [[BridgeIOAnnotation]]s
  *
  * @param leafTypeMap Provides the means to rebuild chisel-types that can
  * "link" against the transformed RTL (FIRRTL), and associate specific
  * annotations with those types..
  */
case class SimWrapperConfig(annotations: Seq[Annotation], leafTypeMap: Map[ReferenceTarget, firrtl.ir.Port])

/**
  * A convienence mixin that preprocesses the [[SimWrapperConfig]]
  */
trait UnpackedWrapperConfig {
  def config: SimWrapperConfig
  val leafTypeMap = config.leafTypeMap
  val chAnnos     = new mutable.ArrayBuffer[FAMEChannelConnectionAnnotation]()
  val bridgeAnnos = new mutable.ArrayBuffer[BridgeIOAnnotation]()
  val fanoutAnnos = new mutable.ArrayBuffer[FAMEChannelFanoutAnnotation]()
  config.annotations collect {
    case fcca: FAMEChannelConnectionAnnotation => chAnnos += fcca
    case ba: BridgeIOAnnotation => bridgeAnnos += ba
    case ffa: FAMEChannelFanoutAnnotation => fanoutAnnos += ffa
  }
}


/**
 *  Represents the interface of the target to which bridges connect.
 */
trait TargetChannelIO {
  /** Mapping of all output pipe channels. */
  def wireOutputPortMap: Map[String, ReadyValidIO[Data]]
  /** Mapping of all input pipe channels. */
  def wireInputPortMap: Map[String, ReadyValidIO[Data]]
  /** Mapping of output ready-valid channels. */
  def rvOutputPortMap: Map[String, TargetRVPortType]
  /** Mapping of input ready-valid channels. */
  def rvInputPortMap: Map[String, TargetRVPortType]
  /** Channel carrying clock tokens to the target. */
  def clockElement: (String, DecoupledIO[Data])
}

/**
  * Builds a Record of tokenized interfaces based on a set of [[FAMEChannelConnectionAnnotations]].
  * Chisel-types are reconstructed by looking up a FIRRTL type in [[SimWrapperConfig].leafTypeMap
  * which can then be mapped back into a primitive chisel type.
  *
  * This is instantiated twice:
  * 1) On the [[TargetBox]], to build a chisel-interface that can link against the FIRRTL
  * 2) To build the SimWrapper's IO. This has the subset of channel interfaces present on the [[TargetBox]]
  * that are connected to bridges.
  *
  * This class includes many members that permit looking up record elements by
  * channel type, and by channel name instead of using the underlying Chisel element
  * name.
  */

abstract class ChannelizedWrapperIO(val config: SimWrapperConfig)
    extends Record with UnpackedWrapperConfig with TargetChannelIO {

  def regenTypesFromField(name: String, tpe: firrtl.ir.Type): Seq[(String, ChLeafType)] = tpe match {
    case firrtl.ir.BundleType(fields) => fields.flatMap(f => regenTypesFromField(prefixWith(name, f.name), f.tpe))
    case firrtl.ir.UIntType(width: firrtl.ir.IntWidth) => Seq(name -> UInt(width.width.toInt.W))
    case firrtl.ir.SIntType(width: firrtl.ir.IntWidth) => Seq(name -> SInt(width.width.toInt.W))
    case _ => throw new RuntimeException(s"Unexpected type in token payload: ${tpe}.")
  }

  def regenTypes(refTargets: Seq[ReferenceTarget]): Seq[(String, ChLeafType)] = {
    val port = leafTypeMap(refTargets.head.copy(component = Seq()))
    val fieldName = refTargets.head.component.headOption match {
      case Some(firrtl.annotations.TargetToken.Field(fName)) => fName
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
    case ch @ FAMEChannelConnectionAnnotation(_,DecoupledForwardChannel(_,Some(vsrc),_,_), _, Some(srcs),_) =>
      ch -> regenPayloadType(srcs.filterNot(_ == vsrc))
    case ch @ FAMEChannelConnectionAnnotation(_,DecoupledForwardChannel(_,_,_,Some(vsink)), _, _, Some(sinks)) =>
      ch -> regenPayloadType(sinks.filterNot(_ == vsink))
  }).toMap

  val wireTypeMap: Map[FAMEChannelConnectionAnnotation, ChLeafType] = chAnnos.collect({
    case ch @ FAMEChannelConnectionAnnotation(_,fame.PipeChannel(_),_,Some(srcs),_) => ch -> regenWireType(srcs)
    case ch @ FAMEChannelConnectionAnnotation(_,fame.PipeChannel(_),_,_,Some(sinks)) => ch -> regenWireType(sinks)
  }).toMap

  val wireElements = mutable.ArrayBuffer[(String, ReadyValidIO[Data])]()

  // Identical source sets can be shared across multiple [[FAMEChannelConnectionAnnotations]],
  // only generate a port for the first one we visit.
  val visitedSourcePorts = mutable.LinkedHashMap[Seq[ReferenceTarget], ReadyValidIO[Data]]()
  val wirePortMap: Map[String, WirePortTuple] = chAnnos.collect({
    case ch @ FAMEChannelConnectionAnnotation(globalName, fame.PipeChannel(_), _, sources, sinks) => {
      val sinkP = sinks.map({ tRefs =>
        val name = tRefs.head.ref.stripSuffix("_bits")
        val port = Flipped(Decoupled(wireTypeMap(ch)))
        wireElements += name -> port
        port
      })
      val sourceP = sources.map({ tRefs =>
        visitedSourcePorts.getOrElse(tRefs, {
          val name = tRefs.head.ref.stripSuffix("_bits")
          val port = Decoupled(wireTypeMap(ch))
          wireElements += name -> port
          visitedSourcePorts(tRefs) = port
          port
        })
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


  val rvElements = mutable.ArrayBuffer[(String, ReadyValidIO[Data])]()

  // Using a channel's globalName; look up it's associated port tuple
  val rvPortMap: Map[String, TargetRVPortTuple] = chAnnos.collect({
    case ch @ FAMEChannelConnectionAnnotation(globalName, info@DecoupledForwardChannel(_,_,_,_), _, leafSources, leafSinks) =>
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

class ClockRecord(numClocks: Int) extends Record {
  override val elements = ListMap(Seq.tabulate(numClocks)(i => s"_$i" -> Clock()):_*)
  override def cloneType = new ClockRecord(numClocks).asInstanceOf[this.type]
}

class TargetBoxIO(config: SimWrapperConfig) extends ChannelizedWrapperIO(config) {

  def regenClockType(refTargets: Seq[ReferenceTarget]): Data = refTargets.size match {
    case 1 => Clock()
    case size => new ClockRecord(refTargets.size)
  }

  val clockElement: (String, DecoupledIO[Data]) = chAnnos.collectFirst({
    case ch @ FAMEChannelConnectionAnnotation(globalName, fame.TargetClockChannel(_, _), _, _, Some(sinks)) =>
      sinks.head.ref.stripSuffix("_bits") -> Flipped(Decoupled(regenClockType(sinks)))
  }).get

  val hostClock = Input(Clock())
  val hostReset = Input(Bool())
  override val elements = ListMap((Seq(clockElement) ++ wireElements ++ rvElements):_*) ++
    // Untokenized ports
    ListMap("hostClock" -> hostClock, "hostReset" -> hostReset)
  override def cloneType: this.type = new TargetBoxIO(config).asInstanceOf[this.type]
}

class TargetBox(config: SimWrapperConfig) extends BlackBox {
  val io = IO(new TargetBoxIO(config))
}

class SimWrapperChannels(config: SimWrapperConfig) extends ChannelizedWrapperIO(config) {

  def regenClockType(refTargets: Seq[ReferenceTarget]): Vec[Bool] = Vec(refTargets.size, Bool())

  val clockElement: (String, DecoupledIO[Vec[Bool]]) = chAnnos.collectFirst({
    case ch @ FAMEChannelConnectionAnnotation(globalName, fame.TargetClockChannel(_,_), _, _, Some(sinks)) =>
      sinks.head.ref.stripSuffix("_bits") -> Flipped(Decoupled(regenClockType(sinks)))
  }).get

  override val elements = ListMap((Seq(clockElement) ++ wireElements ++ rvElements):_*)
  override def cloneType: this.type = new SimWrapperChannels(config).asInstanceOf[this.type]
}

/**
  * The SimWrapper is the shim between the transformed RTL and the rest of the Chisel-generated simulator
  * collateral.
  * 1) It instantiates the tranformed target (now a collection of unconnected, decoupled models)
  * 2) Generates channels to interconnect those models and bridges by analyzing [[FAMEChannelConnectionAnnotation]]s.
  * 3) Exposes ReadyValid interfaces for all channels sourced or sunk by a bridge as I/O
  */
class SimWrapper(val config: SimWrapperConfig)(implicit val p: Parameters) extends Module with UnpackedWrapperConfig {
  outer =>
  // Filter FCCAs presented to the top-level IO constructor. Remove all FCCAs:
  // - That are loopback channels (these don't connect to bridges).
  // - All but the first FCCA in a fanout from a bridge source, preventing
  //   duplication of the source port.
  val isSecondaryFanout = fanoutAnnos.flatMap(_.channelNames.tail).toSet

  val outerConfig = config.copy(annotations = config.annotations.filterNot {
    case fca @ FAMEChannelConnectionAnnotation(_,_,_,Some(_), Some(_)) => true
    case fca @ FAMEChannelConnectionAnnotation(_,_,_,_,Some(_)) => isSecondaryFanout(fca.globalName)
    case o => false
  })

  val channelPorts = IO(new SimWrapperChannels(outerConfig))
  val target = Module(new TargetBox(config))

  // Indicates SimulationMapping which module we want to replace with the simulator
  annotate(new ChiselAnnotation { def toFirrtl =
    TargetBoxAnnotation(target.toAbsoluteTarget)
  })

  target.io.hostReset := reset.asBool
  target.io.hostClock := clock
  import chisel3.ExplicitCompileOptions.NotStrict // FIXME

  def getPipeChannelType(chAnno: FAMEChannelConnectionAnnotation): ChLeafType = {
    target.io.wireTypeMap(chAnno)
  }

  /**
    *  Implements a pipe channel.
    *
    *  @param chAnnos A group of [[FAMEChannelFanoutAnnotation]]s that have a common source. Groups
    *  with size > 1 represent a fanout connection in the source RTL. Each source token will be duplicated
    *  and enqueued into each channel.
    *
    *  @param primaryChannelName For fanouts that are sourced by a bridge, this provides
    *  the unique chname used to look up the bridge-side interface in channelPorts.
    */
  def genPipeChannel(chAnnos: Iterable[FAMEChannelConnectionAnnotation], primaryChannelName: String):
      Iterable[PipeChannel[ChLeafType]] = {
    // Generate a channel queue for each annotation, leaving enq disconnected
    val queues = for (chAnno <- chAnnos) yield {
      require(chAnno.sources == None || chAnno.sources.get.size == 1, "Can't aggregate wire-type channels yet")
      require(chAnno.sinks   == None || chAnno.sinks  .get.size == 1, "Can't aggregate wire-type channels yet")
      val latency = chAnno.channelInfo.asInstanceOf[fame.PipeChannel].latency
      val channel = Module(new PipeChannel(getPipeChannelType(chAnno), latency))
      channel suggestName s"PipeChannel_${chAnno.globalName}"

      target.io.wirePortMap(chAnno.globalName).sink match {
        case Some(sinkP) => sinkP <> channel.io.out
        case None => channelPorts.wireOutputPortMap(chAnno.globalName) <> channel.io.out
      }
      channel
    }

    val srcP = target.io.wirePortMap(primaryChannelName).source.getOrElse(
      channelPorts.wireInputPortMap(primaryChannelName)
    )
   // Do the channel forking on the enq side. Enqueue the token only if all
   // channels can accept a new one.
    val helper = DecoupledHelper((srcP.valid +: queues.map(_.io.in.ready).toSeq):_*)
    for (q <- queues) {
      q.io.in.bits := srcP.bits
      q.io.in.valid := helper.fire(q.io.in.ready)
    }
    srcP.ready := helper.fire(srcP.valid)

    queues
  }

  def genClockChannel(chAnno: FAMEChannelConnectionAnnotation): Unit = {
    val clockTokens = channelPorts.clockElement._2
    target.io.clockElement._2.valid := clockTokens.valid
    clockTokens.ready := target.io.clockElement._2.ready
    target.io.clockElement._2.bits match {
      case port: Clock => port := clockTokens.bits(0).asClock
      case port: ClockRecord => port.elements.zip(clockTokens.bits).foreach({ case ((_, p), i) => p := i.asClock})
    }
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
      // A channel is considered "flipped" if it's sunk by the tranformed RTL (sourced by a bridge)
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

      channel.io.targetReset.bits := false.B
      channel.io.targetReset.valid := true.B
      channel
  }

  // Generate all ready-valid channels
  val rvChannels = chAnnos.collect({
    case ch @ FAMEChannelConnectionAnnotation(_,fame.DecoupledForwardChannel(_,_,_,_),_,_,_) => genReadyValidChannel(ch)
  })

  val pipeChannelFCCAs  = chAnnos.collect {
    case ch @ FAMEChannelConnectionAnnotation(name, fame.PipeChannel(_),_,_,_) => ch
  }

  // Pipe channels can have multiple sinks for each source. Group FCCAs that
  // fanout using the first channel name in the [[FAMEChannelFanoutAnnotation]] as the unique
  // identifier for each group.
  val channelToFanoutName = fanoutAnnos.flatMap({ anno => anno.channelNames.map {
    name => name -> anno.channelNames.head
  }}).toMap
  val channelGroups = pipeChannelFCCAs.groupBy { anno =>
    channelToFanoutName.getOrElse(anno.globalName, anno.globalName) }
  channelGroups foreach { case (name,  annos) => genPipeChannel(annos, name) }

  // Generate clock channels
  val clockChannels = chAnnos.collect({case ch @ FAMEChannelConnectionAnnotation(_, fame.TargetClockChannel(_,_),_,_,_)  => ch })
  require(clockChannels.size == 1)
  genClockChannel(clockChannels.head)
}
