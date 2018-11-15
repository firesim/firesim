// See LICENSE for license details.

package midas

import core._
import widgets._
import platform._
import strober.core._
import junctions.{NastiKey, NastiParameters}
import freechips.rocketchip.config.{Parameters, Config, Field}
import freechips.rocketchip.unittest.UnitTests

trait PlatformType
case object Zynq extends PlatformType
case object F1 extends PlatformType
case object Platform extends Field[PlatformType]
// Switches to synthesize prints and assertions
case object SynthAsserts extends Field[Boolean]
case object SynthPrints extends Field[Boolean]
// Exclude module instances from assertion and print synthesis
// Tuple of Parent Module (where the instance is instantiated) and the instance name
case object ExcludeInstanceAsserts extends Field[Seq[(String, String)]](Seq())
case object ExcludeInstancePrints extends Field[Seq[(String, String)]](Seq())
case object PrintPorts extends Field[Seq[(String, Int)]]
// Excludes from synthesis prints whose message contains one of the following substrings
case object PrintExcludes extends Field[Seq[(String)]](Seq())
case object EnableSnapshot extends Field[Boolean]
case object HasDMAChannel extends Field[Boolean]
case object KeepSamplesInMem extends Field[Boolean]
case object MemModelKey extends Field[Option[Parameters => MemModel]]
case object EndpointKey extends Field[EndpointMap]

class SimConfig extends Config((site, here, up) => {
  case TraceMaxLen      => 1024
  case SRAMChainNum     => 1
  case ChannelLen       => 16
  case ChannelWidth     => 32
  case DaisyWidth       => 32
  case SynthAsserts     => false
  case SynthPrints      => false
  case EnableSnapshot   => false
  case KeepSamplesInMem => true
  case CtrlNastiKey     => NastiParameters(32, 32, 12)
  case DMANastiKey      => NastiParameters(512, 64, 6)
  case EndpointKey      => EndpointMap(Seq(
    new SimNastiMemIO, new SimAXI4MemIO, new AssertBundleEndpoint, new PrintRecordEndpoint))
  case MemModelKey      => Some((p: Parameters) => new SimpleLatencyPipe()(p))
  case FpgaMMIOSize     => BigInt(1) << 12 // 4 KB
  case MidasLLCKey      => None
  case AXIDebugPrint    => false
  case HostMemChannelNastiKey => NastiParameters(64, 32, 6)
  case HostMemNumChannels => 1

  // TODO: Handle ID width.
  case MemNastiKey      => site(HostMemChannelNastiKey).copy(
    addrBits = chisel3.util.log2Ceil(site(HostMemNumChannels)) + site(HostMemChannelNastiKey).addrBits)
})

class ZynqConfig extends Config(new Config((site, here, up) => {
  case Platform       => Zynq
  case HasDMAChannel  => false
  case MasterNastiKey => site(CtrlNastiKey)
}) ++ new SimConfig)

class ZynqConfigWithSnapshot extends Config(new Config((site, here, up) => {
  case EnableSnapshot => true
}) ++ new ZynqConfig)

//we are assuming the host address size is 2^addrBits
class F1Config extends Config(new Config((site, here, up) => {
  case Platform       => F1
  case HasDMAChannel  => true
  case CtrlNastiKey   => NastiParameters(32, 25, 12)
  case MasterNastiKey => site(CtrlNastiKey)
  case HostMemNumChannels => 4
}) ++ new SimConfig)

class F1ConfigWithSnapshot extends Config(new Config((site, here, up) => {
  case EnableSnapshot => true
}) ++ new F1Config)

