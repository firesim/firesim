//See LICENSE for license details.

package firesim.midasexamples

import midas._
import midas.widgets._
import freechips.rocketchip.config.{Config, Parameters}
import freechips.rocketchip.groundtest._
import freechips.rocketchip.rocket.{DCacheParams}
import freechips.rocketchip.subsystem.{WithExtMemSize, WithoutTLMonitors}
import junctions._
import firesim.firesim.{WithDRAMCacheKey, WithNICKey, WithMemBladeKey}
import memblade.cache.DRAMCacheKey
import icenet.IceNetConsts.{NET_IF_WIDTH, NET_IF_BYTES}

class WithDRAMCacheTraceGen extends Config((site, here, up) => {
  case GroundTestTilesKey => Seq.fill(2) {
    TraceGenParams(
      dcache = Some(new DCacheParams(nSets = 16, nWays=1)),
      wordBits = NET_IF_WIDTH,
      addrBits = 40,
      addrBag = {
        val nSets = site(DRAMCacheKey).nSets
        val nWays = site(DRAMCacheKey).nWays
        val spanBytes = site(DRAMCacheKey).spanBytes
        val chunkBytes = site(DRAMCacheKey).chunkBytes
        val nChunks = 2
        val nSpans = site(DRAMCacheKey).nBanks
        List.tabulate(nWays + 1) { i =>
          Seq.tabulate(nChunks) { j =>
            Seq.tabulate(nSpans) { k =>
              BigInt(
                (k * spanBytes) +
                (j * chunkBytes) +
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



// This is incomplete and must be mixed into a complete platform config
class DefaultMIDASConfig extends Config(new Config((site, here, up) => {
    case SynthAsserts => true
    case SynthPrints => true
}) ++ new Config(new firesim.firesim.WithDefaultMemModel))

class PointerChaserConfig extends Config((site, here, up) => {
  case MemSize => BigInt(1 << 30) // 1 GB
  case NMemoryChannels => 1
  case CacheBlockBytes => 64
  case CacheBlockOffsetBits => chisel3.util.log2Up(here(CacheBlockBytes))
  case NastiKey => NastiParameters(dataBits = 64, addrBits = 32, idBits = 3)
})

class DRAMCacheTraceGenConfig extends Config(
  new WithDRAMCacheTraceGen ++
  new WithDRAMCacheKey ++
  new WithMemBladeKey ++
  //new WithExtMemSize(8L << 30) ++
  new WithoutTLMonitors ++
  new WithNICKey ++
  new freechips.rocketchip.system.DefaultConfig)
