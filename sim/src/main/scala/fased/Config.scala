//See LICENSE for license details.

package firesim.fased

import freechips.rocketchip.config.{Field, Config}
import freechips.rocketchip.subsystem.WithoutTLMonitors
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy.{AddressSet, RegionType, TransferSizes}
import freechips.rocketchip.tilelink._

object AXI4SlavePort extends Field[AXI4SlavePortParameters]
object MaxTransferSize extends Field[Int](64)
object BeatBytes extends Field[Int](8)
object IDBits extends Field[Int](4)
object NumTransactions extends Field[Int](10000)
object MaxFlight extends Field[Int](128)

class WithSlavePortParams extends Config((site, here, up) => {
    case AXI4SlavePort => AXI4SlavePortParameters(
      slaves = Seq(AXI4SlaveParameters(
        address       = Seq(AddressSet(BigInt(0), BigInt(0xFFFF))),
        regionType    = RegionType.UNCACHED,
        executable    = true,
        supportsWrite = TransferSizes(1, site(MaxTransferSize)),
        supportsRead  = TransferSizes(1, site(MaxTransferSize)),
        interleavedId = Some(0))),
      beatBytes = site(BeatBytes))
})

class DefaultConfig extends Config(
  new WithoutTLMonitors ++
  new WithSlavePortParams
)

// Platform Configs

class DefaultF1Config extends Config(
  new firesim.firesim.WithDefaultMemModel ++
  new midas.F1Config)

class DDR3Config extends Config(
  // THE FOLLOWING CONFIG IS BROKEN AF.
  //new firesim.firesim.FRFCFS16GBQuadRankLLC4MB3Div ++
  // Splay it out instead
  new firesim.firesim.WithFuncModelLimits(32,32) ++
  new firesim.firesim.WithLLCModel(4096, 8) ++
  new firesim.firesim.WithDDR3FRFCFS(8, 8) ++
  new firesim.firesim.WithDefaultMemModel(3) ++
  new DefaultF1Config)
