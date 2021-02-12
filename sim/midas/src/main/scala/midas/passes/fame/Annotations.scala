package midas.passes.fame

import firrtl._
import annotations._

import midas.targetutils.FAMEAnnotation
import midas.widgets.RationalClock

/**
  * An annotation that describes the ports that constitute one channel
  * from the perspective of a particular module that will be replaced
  * by a simulation model. Note that this describes the channels as
  * they appear locally from within the module, so this annotation will
  * apply to *all* instances of that module.
  *
  * Upon creation, this annotation is associated with a particular
  * target RTL module M that will eventually be transformed into a FAME
  * model. This module must only be instantiated at the top level.
  *
  * @param localName  refers to the name of the channel within the scope of the
  *  eventual FAME model.  This will be used as the channel’s port
  *  name in the model. It will also be used to identify
  *  microarchitectural state associated with the channel
  *
  * @param ports  a list of the ports that are grouped into the channel.  The
  *  ReferenceTargets should be rooted at M, since this information is
  *  local to the module. This is also what associates the annotation
  *  with a given module M
  */
case class FAMEChannelPortsAnnotation(
  localName: String,
  clockPort: Option[ReferenceTarget],
  ports: Seq[ReferenceTarget],
  timestamped: Boolean) extends Annotation with FAMEAnnotation {
  def update(renames: RenameMap): Seq[Annotation] = {
    val renamer = RTRenamer.exact(renames)
    val nonZeroRenamer = RTRenamer.nonZero(renames)
    val updatedPorts = if (timestamped) {
      // Timestamped ports are transformed to include aggregates
      ports.flatMap(nonZeroRenamer)
    } else {
      ports.map(renamer)
    }

    Seq(FAMEChannelPortsAnnotation(localName, clockPort.map(renamer), updatedPorts, timestamped))
  }
  override def getTargets: Seq[ReferenceTarget] = clockPort ++: ports
}

/**
  * An annotation that describes the top-level connectivity of
  * channels on different model instances.
  *
  * @param globalName  a globally unique name for this channel connection
  *
  * @param channelInfo  describes the type of the channel (Wire, Forward/Reverse
  *  Decoupled)
  *
  * @param clock the *source* of the clock (if any) associated with this channel
  *
  * @note The clock source must be a port on the model side of the channel
  */
case class FAMEChannelConnectionAnnotation(
  globalName: String,
  channelInfo: FAMEChannelInfo,
  clock: Option[ReferenceTarget],
  sources: Option[Seq[ReferenceTarget]],
  sinks: Option[Seq[ReferenceTarget]]) extends Annotation with FAMEAnnotation with HasSerializationHints {
  def update(renames: RenameMap): Seq[Annotation] = {
    val renamer = RTRenamer.exact(renames)
    Seq(FAMEChannelConnectionAnnotation(globalName, channelInfo.update(renames), clock.map(renamer), sources.map(_.map(renamer)), sinks.map(_.map(renamer))))
  }
  def typeHints(): Seq[Class[_]] = Seq(channelInfo.getClass)

  def getBridgeModule(): String = sources.getOrElse(sinks.get).head.module

  def moveFromBridge(portName: String): FAMEChannelConnectionAnnotation = {
    def updateRT(rT: ReferenceTarget): ReferenceTarget = ModuleTarget(rT.circuit, rT.circuit).ref(portName).field(rT.ref)

    require(sources == None || sinks == None, "Bridge-connected channels cannot loopback")
    val rTs = sources.getOrElse(sinks.get) ++ clock ++ (channelInfo match {
      case i: DecoupledForwardChannel => Seq(i.readySink.getOrElse(i.readySource.get))
      case other => Seq()
    })

    val localRenames = RenameMap(Map((rTs.map(rT => rT -> Seq(updateRT(rT)))):_*))
    copy(globalName = s"${portName}_${globalName}").update(localRenames).head.asInstanceOf[this.type]
  }

  override def getTargets: Seq[ReferenceTarget] = clock ++: (sources.toSeq.flatten ++ sinks.toSeq.flatten)
}

// Helper factory methods for generating common patterns
object FAMEChannelConnectionAnnotation {
  def implicitlyClockedLoopback(
    globalName: String,
    channelInfo: FAMEChannelInfo,
    sources: Seq[ReferenceTarget],
    sinks: Seq[ReferenceTarget]): FAMEChannelConnectionAnnotation =
    FAMEChannelConnectionAnnotation(globalName, channelInfo, None, Some(sources), Some(sinks))

  def sink(
    globalName: String,
    channelInfo: FAMEChannelInfo,
    clock: Option[ReferenceTarget],
    sinks: Seq[ReferenceTarget]): FAMEChannelConnectionAnnotation =
  FAMEChannelConnectionAnnotation(globalName, channelInfo, clock, None, Some(sinks))

  def implicitlyClockedSink(
    globalName: String,
    channelInfo: FAMEChannelInfo,
    sinks: Seq[ReferenceTarget]): FAMEChannelConnectionAnnotation = sink(globalName, channelInfo, None, sinks)

  def source(
    globalName: String,
    channelInfo: FAMEChannelInfo,
    clock: Option[ReferenceTarget],
    sources: Seq[ReferenceTarget]): FAMEChannelConnectionAnnotation =
  FAMEChannelConnectionAnnotation(globalName, channelInfo, clock, Some(sources), None)

  def implicitlyClockedSource(
    globalName: String,
    channelInfo: FAMEChannelInfo,
    sources: Seq[ReferenceTarget]): FAMEChannelConnectionAnnotation = source(globalName, channelInfo, None, sources)
}

/**
  * An annotation that lables a set of [[FAMEChannelConnectionAnnotation]]s as originating from
  * the same source. For channels sourced by a model (non-bridge) [[FAMEChannelConnectionAnnotation]]s that fanout
  * should share identical sources. However, channels sourced by bridge currently have their sources param set to None, and so
  * this annotation is necessary to re-associate them.
  *
  * @param channelNames  The list of fanout channels
  */
case class FAMEChannelFanoutAnnotation(channelNames: Seq[String]) extends NoTargetAnnotation with FAMEAnnotation

/**
  * Describes the type of the channel (Wire, Forward/Reverse
  * Decoupled)
  */
sealed trait FAMEChannelInfo {
  def update(renames: RenameMap): FAMEChannelInfo = this
  def hasTimestamp: Boolean
}

sealed trait Untimestamped { this: FAMEChannelInfo =>
  val hasTimestamp = false
}

sealed trait Timestamped { this: FAMEChannelInfo =>
  val hasTimestamp = true
}
/**
  * Indicates that a channel connection is a pipe with <latency> register stages
  * Setting latency = 0 models a wire
  *
  * TODO: How to handle registers that are reset? Add an Option[RT]?
  */
case class PipeChannel(val latency: Int) extends FAMEChannelInfo with Untimestamped

/**
  * Indicates that a channel connection is the reverse (ready) half of
  * a decoupled target connection. Since the forward half incorporates
  * references to the ready signals, this channel contains no signal
  * references.
  */
case object DecoupledReverseChannel extends FAMEChannelInfo with Untimestamped

/**
  * Indicates that a channel connection carries target clocks
  */
case class TargetClockChannel(clockInfo: Seq[RationalClock])  extends FAMEChannelInfo with Timestamped

/**
  * Indicates the channel is a wire-type connection with timestamped tokens. This is used to drive signals
  * that are sunk by clock-generating models
  */
case object ClockControlChannel extends FAMEChannelInfo with Timestamped

/**
  * Indicates the channel carries a single asynchronous reset
  */
case object AsyncResetChannel extends FAMEChannelInfo with Timestamped

/**
  * Indicates that a channel connection is the forward (valid) half of
  * a decoupled target connection.
  *
  * @param readySink  sink port component of the corresponding reverse channel
  *
  * @param validSource  valid port component from this channel's sources
  *
  * @param readySource  source port component of the corresponding reverse channel
  *
  * @param validSink  valid port component from this channel's sinks
  *
  * @note  (readySink, validSource) are on one model, (readySource, validSink) on the other
  */
case class DecoupledForwardChannel(
  readySink: Option[ReferenceTarget],
  validSource: Option[ReferenceTarget],
  readySource: Option[ReferenceTarget],
  validSink: Option[ReferenceTarget]) extends FAMEChannelInfo with Untimestamped {
  override def update(renames: RenameMap): DecoupledForwardChannel = {
    val renamer = RTRenamer.exact(renames)
    DecoupledForwardChannel(
      readySink.map(renamer),
      validSource.map(renamer),
      readySource.map(renamer),
      validSink.map(renamer))
  }
}

// Helper factory methods for generating bridge annotations that have only sinks or sources
object DecoupledForwardChannel {
  def sink(valid: ReferenceTarget, ready: ReferenceTarget) =
    DecoupledForwardChannel(None, None, Some(ready), Some(valid))

  def source(valid: ReferenceTarget, ready: ReferenceTarget) =
    DecoupledForwardChannel(Some(ready), Some(valid), None, None)
}

/**
  * Specifies what form of FAME transform should be applied when
  * generated a simulation model from a target module.
  */
abstract class FAMETransformType

/**
  * When tied to a target in a FAMETransformAnnotation, this specifies
  * that the module should be transformed with the basic LI-BDN
  * compliant FAME1 transform.
  */
case object FAME1Transform extends FAMETransformType

/**
  * Indicates that a particular target module from the "AQB" canonical
  * form should be transformed to a FAME model.
  *
  * @param transformType  Describes which variant of the FAME transform
  *  should be applied to the target module. Currently, the
  *  FAME1Transform object is the only value that this can take.
  *
  * @param target  Points to the target module to be transformed. Since
  *  this is a ModuleTarget, all instances at the top level will be
  *  transformed identically.
  */
case class FAMETransformAnnotation(
  transformType: FAMETransformType,
  target: ModuleTarget) extends SingleTargetAnnotation[ModuleTarget] with FAMEAnnotation {
  def targets = Seq(target)
  def duplicate(n: ModuleTarget) = this.copy(transformType, n)
}

/**
  * Indicates that a particular target instance should be promoted one
  * level in the hierarchy. The specified instance will be pulled out
  * of its parent module and will reside in its "grandparent" module
  * after the PromoteSubmodule transform has run.
  *
  * @param target  The instance to be promoted. Note that this must
  *  be a *local* instance target, as all instances of the parent
  *  module will be transformed identically.
  */
case class PromoteSubmoduleAnnotation(
  target: InstanceTarget) extends SingleTargetAnnotation[InstanceTarget] with FAMEAnnotation {
  def targets = Seq(target)
  def duplicate(n: InstanceTarget) = this.copy(n)
}

abstract class FAMEGlobalSignal extends SingleTargetAnnotation[ReferenceTarget] with FAMEAnnotation {
  val target: ReferenceTarget
  def targets = Seq(target)
  def duplicate(n: ReferenceTarget): FAMEGlobalSignal
}

case class FAMEHostClock(target: ReferenceTarget) extends FAMEGlobalSignal {
  def duplicate(t: ReferenceTarget): FAMEHostClock = this.copy(t)
}

case class FAMEHostReset(target: ReferenceTarget) extends FAMEGlobalSignal {
  def duplicate(t: ReferenceTarget): FAMEHostReset = this.copy(t)
}

abstract class MemPortAnnotation extends Annotation with FAMEAnnotation {
  val en: ReferenceTarget
  val addr: ReferenceTarget
}

object ModelReadPort {
  def apply(rpt: ReferenceTarget) =
    new ModelReadPort(
      rpt.field("data"),
      rpt.field("addr"),
      rpt.field("en"))
}

case class ModelReadPort(
  data: ReferenceTarget,
  addr: ReferenceTarget,
  en: ReferenceTarget) extends MemPortAnnotation {
  def update(renames: RenameMap): Seq[Annotation] = {
    val renamer = RTRenamer.exact(renames)
    Seq(ModelReadPort(renamer(data), renamer(addr), renamer(en)))
  }
  override def getTargets: Seq[ReferenceTarget] = Seq(data, addr, en)
}

object ModelWritePort {
  def apply(rpt: ReferenceTarget) =
    new ModelWritePort(
      rpt.field("data"),
      rpt.field("mask"),
      rpt.field("addr"),
      rpt.field("en"))
}

case class ModelWritePort(
  data: ReferenceTarget,
  mask: ReferenceTarget,
  addr: ReferenceTarget,
  en: ReferenceTarget) extends MemPortAnnotation {
  def update(renames: RenameMap): Seq[Annotation] = {
    val renamer = RTRenamer.exact(renames)
    Seq(ModelWritePort(renamer(data), renamer(mask), renamer(addr), renamer(en)))
  }
  override def getTargets: Seq[ReferenceTarget] = Seq(data, mask, addr, en)
}

object ModelReadWritePort {
  def apply(rpt: ReferenceTarget) =
    new ModelReadWritePort(
      rpt.field("wmode"),
      rpt.field("rdata"),
      rpt.field("wdata"),
      rpt.field("wmask"),
      rpt.field("addr"),
      rpt.field("en"))
}

case class ModelReadWritePort(
  wmode: ReferenceTarget,
  rdata: ReferenceTarget,
  wdata: ReferenceTarget,
  wmask: ReferenceTarget,
  addr: ReferenceTarget,
  en: ReferenceTarget) extends MemPortAnnotation {
  def update(renames: RenameMap): Seq[Annotation] = {
    val renamer = RTRenamer.exact(renames)
    Seq(ModelReadWritePort(renamer(wmode), renamer(rdata), renamer(wdata), renamer(wmask), renamer(addr), renamer(en)))
  }
  override def getTargets: Seq[ReferenceTarget] = Seq(wmode, rdata, wdata, wmask, addr, en)
}

/**
  * A pass that dumps all FAME annotations to a file for debugging.
  */
class EmitFAMEAnnotations(fileName: String) extends firrtl.Transform {
  import firrtl.options.TargetDirAnnotation
  def inputForm = UnknownForm
  def outputForm = UnknownForm

  override def name = s"[Golden Gate] Debugging FAME Annotation Emission Pass: $fileName"

  def execute(state: CircuitState) = {
    val targetDir = state.annotations.collectFirst { case TargetDirAnnotation(dir) => dir }
    val dirName = targetDir.getOrElse(".")
    val outputFile = new java.io.PrintWriter(s"${dirName}/${fileName}")
    val fameAnnos = state.annotations.collect { case fa: FAMEAnnotation => fa }
    outputFile.write(JsonProtocol.serialize(fameAnnos))
    outputFile.close()
    state
  }
}

/**
  * Carries RTs to a series of control signals that move between the hub model and the simulation master
  *
  */
case class PortMetadata(rT: ReferenceTarget, dir: firrtl.ir.Direction, tpe: firrtl.ir.Type)
object PortMetadata {
  def apply(mT: ModuleTarget, p: firrtl.ir.Port): PortMetadata = PortMetadata(mT.ref(p.name), p.direction, p.tpe)
}
case class SimulationControlAnnotation(signals: Map[String, PortMetadata]) extends Annotation with FAMEAnnotation {
  def enclosingModuleName = signals.values.head.rT.module
  def update(renames: RenameMap): Seq[Annotation] = {
    val renamer = RTRenamer.exact(renames)
    Seq(SimulationControlAnnotation(signals.map({ case (name, meta) => name -> meta.copy(rT = renamer(meta.rT)) }).toMap))
  }
}
