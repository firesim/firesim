// See LICENSE for license details.

package midas

import core._
import widgets._
import platform._
import models._
import strober.core._
import junctions.{NastiKey, NastiParameters}
import freechips.rocketchip.config.{Parameters, Config, Field}
import freechips.rocketchip.unittest.UnitTests

import java.io.{File}

// Provides a function to elaborate the top-level platform shim
case object Platform extends Field[(Parameters) => PlatformShim]
// Switches to synthesize prints and assertions
case object SynthAsserts extends Field[Boolean]
case object SynthPrints extends Field[Boolean]
case object TraceTrigger extends Field[Boolean]
// Exclude module instances from assertion and print synthesis
// Tuple of Parent Module (where the instance is instantiated) and the instance name
case object EnableSnapshot extends Field[Boolean]
case object HasDMAChannel extends Field[Boolean]
case object KeepSamplesInMem extends Field[Boolean]

// Enables multi-cycle RAM model generation (as demonstrated in the ICCAD2019 paper)
case object GenerateMultiCycleRamModels extends Field[Boolean](false)
// User provided transforms to run before Golden Gate transformations
// These are constructor functions accept a Parameters instance and produce a
// sequence of firrtl Transforms to run
case object TargetTransforms extends Field[Seq[(Parameters) => Seq[firrtl.Transform]]](Seq())
// User provided transforms to run after Golden Gate transformations
case object HostTransforms extends Field[Seq[(Parameters) => Seq[firrtl.Transform]]](Seq())

// Directory into which output files are dumped. Set by -td when invoking the Stage
case object OutputDir extends Field[File]

class SimConfig extends Config((site, here, up) => {
  case TraceMaxLen      => 1024
  case SRAMChainNum     => 1
  case ChannelLen       => 16
  case ChannelWidth     => 32
  case DaisyWidth       => 32
  case SynthAsserts     => false
  case SynthPrints      => false
  case TraceTrigger     => false
  case EnableSnapshot   => false
  case KeepSamplesInMem => true
  case CtrlNastiKey     => NastiParameters(32, 32, 12)
  case DMANastiKey      => NastiParameters(512, 64, 6)
  case FpgaMMIOSize     => BigInt(1) << 12 // 4 KB
  case AXIDebugPrint    => false
  case HostMemChannelNastiKey => NastiParameters(64, 32, 6)
  case HostMemNumChannels => 1

  case MemNastiKey      => site(HostMemChannelNastiKey).copy(
    addrBits = chisel3.util.log2Ceil(site(HostMemNumChannels)) + site(HostMemChannelNastiKey).addrBits,
    // TODO: We should try to constrain masters to 4 bits of ID space -> but we need to map
    // multiple target-ids on a single host-id in the DRAM timing model to support that
    idBits   = 6
  )
})

class ZynqConfig extends Config(new Config((site, here, up) => {
  case Platform       => (p: Parameters) => new ZynqShim()(p)
  case HasDMAChannel  => false
  case MasterNastiKey => site(CtrlNastiKey)
}) ++ new SimConfig)

class ZynqConfigWithSnapshot extends Config(new Config((site, here, up) => {
  case EnableSnapshot => true
}) ++ new ZynqConfig)

// we are assuming the host-DRAM size is 2^chAddrBits
class F1Config extends Config(new Config((site, here, up) => {
  case Platform       => (p: Parameters) => new F1Shim()(p)
  case HasDMAChannel  => true
  case CtrlNastiKey   => NastiParameters(32, 25, 12)
  case MasterNastiKey => site(CtrlNastiKey)
  case HostMemChannelNastiKey => NastiParameters(64, 34, 16)
  case HostMemNumChannels => 4
}) ++ new SimConfig)

class F1ConfigWithSnapshot extends Config(new Config((site, here, up) => {
  case EnableSnapshot => true
}) ++ new F1Config)

// Turns on all additional synthesizable debug features for checking the
// implementation of the simulator.
class HostDebugFeatures extends Config((site, here, up) => {
  case GenerateTokenIrrevocabilityAssertions => true
})
