//See LICENSE for license details.

package firesim.midasexamples

import midas._
import midas.widgets._
import freechips.rocketchip.config._
import junctions._

import firesim.util.DesiredHostFrequency
import firesim.configs.WithDefaultMemModel

class NoConfig extends Config(Parameters.empty)
// This is incomplete and must be mixed into a complete platform config
class DefaultF1Config extends Config(new Config((site, here, up) => {
    case DesiredHostFrequency => 75
    case SynthAsserts => true
    case midas.GenerateMultiCycleRamModels => true
    case SynthPrints => true
    case TargetTransforms => ((p: Parameters) => Seq(new midas.passes.AutoCounterTransform()(p))) +: up(TargetTransforms, site)
}) ++ new Config(new firesim.configs.WithEC2F1Artefacts ++ new WithDefaultMemModel ++ new midas.F1Config))

class PointerChaserConfig extends Config((site, here, up) => {
  case MemSize => BigInt(1 << 30) // 1 GB
  case NMemoryChannels => 1
  case CacheBlockBytes => 64
  case CacheBlockOffsetBits => chisel3.util.log2Up(here(CacheBlockBytes))
  case NastiKey => NastiParameters(dataBits = 64, addrBits = 32, idBits = 3)
  case Seed => System.currentTimeMillis
})
