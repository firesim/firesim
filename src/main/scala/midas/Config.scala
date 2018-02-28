// See LICENSE for license details.

package midas

import core._
import widgets._
import platform._
import strober.core._
import junctions.{NastiKey, NastiParameters}
import freechips.rocketchip.config.{Parameters, Config, Field}

trait PlatformType
case object Zynq extends PlatformType
case object F1 extends PlatformType
case object Platform extends Field[PlatformType]
case object EnableSnapshot extends Field[Boolean]
case object KeepSamplesInMem extends Field[Boolean]
case object MemModelKey extends Field[Option[Parameters => MemModel]]
case object EndpointKey extends Field[EndpointMap]

class SimConfig extends Config((site, here, up) => {
  case TraceMaxLen      => 1024
  case SRAMChainNum     => 1
  case ChannelLen       => 16
  case ChannelWidth     => 32
  case DaisyWidth       => 32
  case EnableSnapshot   => false
  case KeepSamplesInMem => true
  case CtrlNastiKey     => NastiParameters(32, 32, 12)
  case MemNastiKey      => NastiParameters(64, 32, 6)
  case EndpointKey      => EndpointMap(Seq(new SimNastiMemIO, new SimAXI4MemIO))
  case MemModelKey      => Some((p: Parameters) => new SimpleLatencyPipe()(p))
  case FpgaMMIOSize     => BigInt(1) << 12 // 4 KB
  case MidasLLCKey      => None
})

class ZynqConfig extends Config(new Config((site, here, up) => {
  case Platform       => Zynq
  case MasterNastiKey => site(CtrlNastiKey)
  case SlaveNastiKey  => site(MemNastiKey)
}) ++ new SimConfig)

class ZynqConfigWithSnapshot extends Config(new Config((site, here, up) => {
  case EnableSnapshot => true
}) ++ new ZynqConfig)

class F1Config extends Config(new Config((site, here, up) => {
  case Platform       => F1
  case CtrlNastiKey   => NastiParameters(32, 25, 12)
  case MemNastiKey    => NastiParameters(64, 34, 16)
  case MasterNastiKey => site(CtrlNastiKey)
  case SlaveNastiKey => site(MemNastiKey)
}) ++ new SimConfig)

class F1ConfigWithSnapshot extends Config(new Config((site, here, up) => {
  case EnableSnapshot => true
}) ++ new F1Config)
