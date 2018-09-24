//See LICENSE for license details.

package firesim.midasexamples

import midas._
import midas.widgets._
import freechips.rocketchip.config._
import junctions._

class PointerChaserConfig extends Config((site, here, up) => {
  case MemSize => BigInt(1 << 30) // 1 GB
  case NMemoryChannels => 1
  case CacheBlockBytes => 64
  case CacheBlockOffsetBits => chisel3.util.log2Up(here(CacheBlockBytes))
  case NastiKey => NastiParameters(dataBits = 64, addrBits = 32, idBits = 3)
})
