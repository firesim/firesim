//See LICENSE for license details.

package firesim.midasexamples

import midas._
import midas.widgets._
import freechips.rocketchip.config.{Config, Parameters}
import freechips.rocketchip.groundtest._
import freechips.rocketchip.rocket.{DCacheParams}
import freechips.rocketchip.subsystem.{WithExtMemSize, WithoutTLMonitors, CacheBlockBytes}
import junctions._
import firesim.firesim.{WithDRAMCacheKey, WithNICKey, WithMemBladeKey}
import memblade.cache.DRAMCacheKey
import icenet.IceNetConsts.{NET_IF_WIDTH, NET_IF_BYTES}
import scala.math.min

import firesim.util.DesiredHostFrequency
import firesim.configs.WithDefaultMemModel

class NoConfig extends Config(Parameters.empty)
// This is incomplete and must be mixed into a complete platform config
class DefaultF1Config extends Config(new Config((site, here, up) => {
    case DesiredHostFrequency => 75
    case SynthAsserts => true
    case SynthPrints => true
}) ++ new Config(new WithDefaultMemModel ++ new midas.F1Config))

class PointerChaserConfig extends Config((site, here, up) => {
  case MemSize => BigInt(1 << 30) // 1 GB
  case NMemoryChannels => 1
  case CacheBlockBytes => 64
  case CacheBlockOffsetBits => chisel3.util.log2Up(here(CacheBlockBytes))
  case NastiKey => NastiParameters(dataBits = 64, addrBits = 32, idBits = 3)
  case Seed => System.currentTimeMillis
})
