//See LICENSE for license details.

package firesim.util

import scala.collection.mutable
import scala.reflect.ClassTag

import chisel3._
import chisel3.experimental.{RawModule, DataMirror, Direction}

import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.devices.debug.{ClockedDMIIO}

import midas.widgets.{PeekPokeEndpoint, IsEndpoint}
import midas.models.{FASEDEndpoint, AXI4BundleWithEdge}
import junctions.{NastiKey, NastiParameters}

case object EndpointKey extends Field[Seq[IOMatcher]](Seq())
case object MemModelKey extends Field[Parameters => FASEDEndpoint]

trait IOMatcher {
  def matchType: PartialFunction[(Data, Parameters), Seq[IsEndpoint]]
}

abstract class EndpointIOMatcher[TargetIOType <: Data : ClassTag, EndpointType <: IsEndpoint] extends IOMatcher {
  def matchType: PartialFunction[(Data, Parameters), Seq[IsEndpoint]] = {
    case (p: TargetIOType, params) if checkPort(p) => apply(p)(params)
  }

  def apply(port: TargetIOType)(implicit p: Parameters): Seq[EndpointType]
  def checkPort(port: TargetIOType): Boolean
}

case class NoEndpointException(port: Data, msg: String) extends Exception(msg)

object FASEDEndpointMatcher extends EndpointIOMatcher[AXI4BundleWithEdge, FASEDEndpoint] {
  def checkPort(port: AXI4BundleWithEdge): Boolean = DataMirror.directionOf(port.w.valid) == Direction.Output
  def apply(axi4: AXI4BundleWithEdge)(implicit p: Parameters): Seq[FASEDEndpoint] = {
    val nastiKey = NastiParameters(axi4.r.bits.data.getWidth,
                                   axi4.ar.bits.addr.getWidth,
                                   axi4.ar.bits.id.getWidth)
    val ep = Module(p(MemModelKey)(p.alterPartial({ case NastiKey => nastiKey })))
    import chisel3.core.ExplicitCompileOptions.NotStrict
    ep.io.axi4 <> axi4
    Seq(ep)
  }
}

object TieOffDebug extends IOMatcher {
  def matchType = {
    case (port: ClockedDMIIO, p: Parameters) => {
      port.dmi.req.valid := false.B
      port.dmi.req.bits := DontCare
      port.dmi.resp.ready := false.B
      port.dmiClock := false.B.asClock
      port.dmiReset := false.B
      Seq()
    }
  }
}

class IOMatchingMIDASEnvironment(dutGen: () => RawModule)(implicit val p: Parameters) extends RawModule {
  val clock = IO(Input(Clock()))
  val reset = WireInit(false.B)


  withClockAndReset(clock, reset) {
    val target = Module(dutGen())
    def defaultMatch: PartialFunction[(Data, Parameters), Seq[IsEndpoint]] =
      { case (p, _) => throw new NoEndpointException(p, s"No provided endpoint can bind to port ${p}")}
    val epMatcher = p(EndpointKey).foldRight(defaultMatch)(_.matchType orElse _)

    val nonClockPorts = target.getPorts.map(_.id).flatMap({
      case c: Clock => None
      case otherPort =>  Some(otherPort)
    })

    val peekOrPokedIO = mutable.ArrayBuffer[(String, Data)]()
    def bindEndpoint(port: Data): Seq[IsEndpoint] = {
      try {
        epMatcher(port, p)
      } catch {
        case NoEndpointException(port: Bool, _) if port.instanceName == "reset" => Seq()
        case NoEndpointException(port: Clock, _) => Seq()
        case NoEndpointException(port: Record, _) =>
          port.elements.flatMap({ case (_, elm) => bindEndpoint(elm) }).toSeq
        case NoEndpointException(port: Vec[_], _) => port.flatMap(bindEndpoint).toSeq
        case NoEndpointException(port, _) => {
          println(s"No endpoint provided for port ${port}; binding to PeekPoke Endpoint")
          val loweredName = port.instanceName.map(_ match {
            case '.' => '_'
            case c => c
          })
          peekOrPokedIO += (loweredName -> port)
          Seq()
        }
      }
    }
    val endpoints = target.getPorts.map(_.id).flatMap(bindEndpoint)
    peekOrPokedIO foreach println
    val peekPokeEndpoint = PeekPokeEndpoint(reset, peekOrPokedIO:_*)
  }
}

