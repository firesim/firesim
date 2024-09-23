// See LICENSE for license details.

package firesim.lib.bridgeutils

import chisel3.{Clock, Data, Flipped, Record}
import chisel3.util.ReadyValidIO

/*
 *  The MIDAS-I legacy HostPort. Bridges using this Class to implement it's host-land interface
 *  consume a _single_ input token and produce a _single_ output token. As such, HostPort is really only
 *  useful for modeling a "tick"-like behavior where no output depends
 *  combinationally on an input token. In this case, the bridge should be
 *  able to enqueue an output token without requiring an input token. (This, to
 *  satisfy LI-BDN's NED property, and to give better simulation performance)
 *
 *  (It is also possible to use this for very simple models where the one or
 *  more outputs depend combinationally on a _single_ input token (the toHost
 *  field))
 */
// We're using a Record here because reflection in Bundle prematurely initializes our lazy vals
class HostPortIO[+T <: Data](private val targetPortProto: T) extends Record with HasChannels {
  val fromHost = new HostReadyValid
  val toHost   = Flipped(new HostReadyValid)
  val hBits    = targetPortProto

  val elements = collection.immutable.ListMap(Seq("fromHost" -> fromHost, "toHost" -> toHost, "hBits" -> hBits): _*)

  def getClock(): Clock = {
    val allTargetClocks = SimUtils.findClocks(targetPortProto)
    require(
      allTargetClocks.nonEmpty,
      s"Target-side bridge interface of ${targetPortProto.getClass} has no clock field.",
    )
    require(
      allTargetClocks.size == 1,
      s"Target-side bridge interface of ${targetPortProto.getClass} has ${allTargetClocks.size} clocks but must define only one.",
    )
    allTargetClocks.head
  }

  // These are lazy because parsePorts needs a directioned gen; these can be called once
  // this Record has been bound to Hardware
  //private lazy val (ins, outs, rvIns, rvOuts) = SimUtils.parsePorts(targetPortProto, alsoFlattenRVPorts = false)
  private lazy val (ins, outs, rvIns, rvOuts) =
    try {
      SimUtils.parsePorts(targetPortProto, alsoFlattenRVPorts = false)
    } catch {
      case e: chisel3.BindingException =>
        SimUtils.parsePorts(hBits, alsoFlattenRVPorts = false)
    }

  def inputWireChannels(): Seq[(Data, String)]              = ins
  def outputWireChannels(): Seq[(Data, String)]             = outs
  def inputRVChannels(): Seq[(ReadyValidIO[Data], String)]  = rvIns
  def outputRVChannels(): Seq[(ReadyValidIO[Data], String)] = rvOuts
  lazy val name2Wire                                        = Map((ins ++ outs).map({ case (wire, name) => name -> wire }): _*)
  lazy val name2ReadyValid                                  = Map((rvIns ++ rvOuts).map({ case (wire, name) => name -> wire }): _*)

  def bridgeChannels(): Seq[BridgeChannel] = {
    val clockRT = getClock.toNamed.toTarget

    inputWireChannels().map({ case (field, chName) =>
      PipeBridgeChannel(
        chName,
        clock   = clockRT,
        sinks   = Seq(),
        sources = Seq(field.toNamed.toTarget),
        latency = 1,
      )
    }) ++
      outputWireChannels().map({ case (field, chName) =>
        PipeBridgeChannel(
          chName,
          clock   = clockRT,
          sinks   = Seq(field.toNamed.toTarget),
          sources = Seq(),
          latency = 1,
        )
      }) ++
      rvIns.map({ case (field, chName) =>
        val (fwdChName, revChName) = SimUtils.rvChannelNamePair(chName)
        val validTarget            = field.valid.toNamed.toTarget
        ReadyValidBridgeChannel(
          fwdChName,
          revChName,
          clock   = getClock.toNamed.toTarget,
          sinks   = Seq(),
          sources = SimUtils.lowerAggregateIntoLeafTargets(field.bits) ++ Seq(validTarget),
          valid   = validTarget,
          ready   = field.ready.toNamed.toTarget,
        )
      }) ++
      rvOuts.map({ case (field, chName) =>
        val (fwdChName, revChName) = SimUtils.rvChannelNamePair(chName)
        val validTarget            = field.valid.toNamed.toTarget
        ReadyValidBridgeChannel(
          fwdChName,
          revChName,
          clock   = getClock.toNamed.toTarget,
          sinks   = SimUtils.lowerAggregateIntoLeafTargets(field.bits) ++ Seq(validTarget),
          sources = Seq(),
          valid   = validTarget,
          ready   = field.ready.toNamed.toTarget,
        )
      })
  }
}

object HostPort {
  def apply[T <: Data](gen: T): HostPortIO[T] = new HostPortIO(gen)
}
