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

class WithDRAMCacheTraceGen extends Config((site, here, up) => {
  case GroundTestTilesKey => Seq.fill(2) {
    TraceGenParams(
      dcache = Some(new DCacheParams(nSets = 16, nWays=1)),
      wordBits = NET_IF_WIDTH,
      addrBits = 40,
      addrBag = {
        val cacheKey = site(DRAMCacheKey)
        val nSets = cacheKey.nSets
        val nWays = cacheKey.nWays
        val spanBytes = cacheKey.spanBytes
        val blockBytes = site(CacheBlockBytes)
        val nBlocks = min(spanBytes/blockBytes, 2)
        val nChannels = cacheKey.nChannels
        val nSpans = cacheKey.nBanksPerChannel * nChannels
        List.tabulate(nWays + 1) { i =>
          Seq.tabulate(nBlocks) { j =>
            Seq.tabulate(nSpans) { k =>
              BigInt(
                (k * spanBytes) +
                (j * blockBytes) +
                (i * nSets * spanBytes))
            }
          }.flatten
        }.flatten
      },
      maxRequests = 1024,
      memStart = site(DRAMCacheKey).baseAddr,
      numGens = 2)
  }
  case DRAMCacheKey => up(DRAMCacheKey).copy(
    extentTableInit = Seq((3, 0)),
    nSets = 1 << 10)
})



class NoConfig extends Config(Parameters.empty)
// This is incomplete and must be mixed into a complete platform config
class DefaultF1Config extends Config(new Config((site, here, up) => {
    case firesim.util.DesiredHostFrequency => 75
    case SynthAsserts => true
    case SynthPrints => true
}) ++ new Config(new firesim.firesim.WithDefaultMemModel ++ new midas.F1Config))

class PointerChaserConfig extends Config((site, here, up) => {
  case MemSize => BigInt(1 << 30) // 1 GB
  case NMemoryChannels => 1
  case CacheBlockBytes => 64
  case CacheBlockOffsetBits => chisel3.util.log2Up(here(CacheBlockBytes))
  case NastiKey => NastiParameters(dataBits = 64, addrBits = 32, idBits = 3)
  case Seed => System.currentTimeMillis
})

class DRAMCacheTraceGenConfig extends Config(
  new WithDRAMCacheTraceGen ++
  new WithDRAMCacheKey ++
  new WithMemBladeKey ++
  //new WithExtMemSize(8L << 30) ++
  new WithoutTLMonitors ++
  new WithNICKey ++
  new freechips.rocketchip.system.DefaultConfig)
