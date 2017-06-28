package midas

import core._
import widgets._
import platform._
import strober.core._
import config.{Parameters, Config, Field}
import junctions.{NastiKey, NastiParameters}

trait PlatformType
case object Zynq extends PlatformType
case object Catapult extends PlatformType
case object Platform extends Field[PlatformType]
case object EnableSnapshot extends Field[Boolean]
case object MemModelKey extends Field[Option[Parameters => MemModel]]
case object EndpointKey extends Field[EndpointMap]

class SimConfig extends Config((site, here, up) => {
  case TraceMaxLen    => 1024
  case SRAMChainNum   => 1
  case ChannelLen     => 16
  case ChannelWidth   => 32
  case DaisyWidth     => 32
  case EnableSnapshot => false
  case CtrlNastiKey   => NastiParameters(32, 32, 12)
  case MemNastiKey    => NastiParameters(64, 32, 6)
  case EndpointKey    => EndpointMap(Seq(new SimNastiMemIO, new SimAXI4MemIO))
  case MemModelKey    => Some((p: Parameters) => new SimpleLatencyPipe()(p))
  case FpgaMMIOSize   => BigInt(1) << 12 // 4 KB
})

class ZynqConfig extends Config(new Config((site, here, up) => {
  case Platform       => Zynq
  case MidasL2Key     => None
  case MasterNastiKey => site(CtrlNastiKey)
  case SlaveNastiKey  => site(MemNastiKey)
}) ++ new SimConfig)

class ZynqConfigWithSnapshot extends Config(new Config((site, here, up) => {
  case EnableSnapshot => true
}) ++ new ZynqConfig)

class CatapultConfig extends Config(new Config((site, here, up) => {
  case Platform       => Catapult
  case PCIeWidth      => 640
  case ChannelWidth   => 64
  case DaisyWidth     => 64
  case SoftRegKey     => SoftRegParam(32, 64)
  case CtrlNastiKey   => NastiParameters(64, 32, 12)
  case NastiKey       => site(CtrlNastiKey)
  case MemModelKey    => None
}) ++ new SimConfig)

class CatapultConfigWithSnapshot extends Config(new Config((site, here, up) => {
  case EnableSnapshot => true
}) ++ new CatapultConfig)
