//See LICENSE for license details.

package firesim.fased

import freechips.rocketchip.config.{Field, Config}
import freechips.rocketchip.subsystem.WithoutTLMonitors
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy.{AddressSet, RegionType, TransferSizes}
import freechips.rocketchip.tilelink._

object AXI4SlavePort extends Field[AXI4SlavePortParameters]

class WithSlavePortParams extends Config((site, here, up) => {
  case AXI4SlavePort =>
    AXI4SlavePortParameters(
      slaves = Seq(AXI4SlaveParameters(
        address       = Seq(AddressSet(BigInt(0), BigInt(0xFFFF))),
        regionType    = RegionType.UNCACHED,
        executable    = true,
        supportsWrite = TransferSizes(1, 128),
        supportsRead  = TransferSizes(1, 128),
        interleavedId = Some(0))),
      beatBytes = 8)
})

class DefaultConfig extends Config(
  new WithoutTLMonitors ++
  new WithSlavePortParams
)
