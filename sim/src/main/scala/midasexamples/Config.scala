//See LICENSE for license details.

package firesim.midasexamples

import midas._
import freechips.rocketchip.config._
import junctions._

import firesim.configs.{WithDefaultMemModel, WithWiringTransform}

class NoConfig extends Config(Parameters.empty)
// This is incomplete and must be mixed into a complete platform config
class BaseMidasExamplesConfig extends Config(
  new WithDefaultMemModel ++
  new WithWiringTransform ++
  new HostDebugFeatures ++
  new Config((site, here, up) => {
    case SynthAsserts => true
    case GenerateMultiCycleRamModels => true
    case EnableModelMultiThreading => true
    case EnableAutoILA => true
    case SynthPrints => true
    case EnableAutoCounter => true
  })
)
class DefaultF1Config extends Config(
  new BaseMidasExamplesConfig ++
  new midas.F1Config
)

class DefaultVitisConfig extends Config(
  new BaseMidasExamplesConfig ++
  new midas.VitisConfig
)

class PointerChaserConfig extends Config((site, here, up) => {
  case MemSize => BigInt(1 << 30) // 1 GB
  case NMemoryChannels => 1
  case CacheBlockBytes => 64
  case CacheBlockOffsetBits => chisel3.util.log2Up(here(CacheBlockBytes))
  case NastiKey => NastiParameters(dataBits = 64, addrBits = 32, idBits = 3)
  case Seed => System.currentTimeMillis
})

class AutoCounterPrintf extends Config((site, here, up) => {
  case AutoCounterUsePrintfImpl => true
})

class NoSynthAsserts extends Config((site, here, up) => {
  case SynthAsserts => false
})
