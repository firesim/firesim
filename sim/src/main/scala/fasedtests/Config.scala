//See LICENSE for license details.
package firesim.fasedtests

import chisel3.util.isPow2

import org.chipsalliance.cde.config.{Config, Field}
import freechips.rocketchip.subsystem.{
  ExtMem,
  MasterPortParams,
  MemoryPortParams,
  WithNMemoryChannels,
  WithoutTLMonitors,
}
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy.{AddressSet, RegionType, TransferSizes}
import firesim.configs._
import firesim.lib.nasti.NastiParameters

case object AXI4SlavePort        extends Field[AXI4SlavePortParameters]
case object MaxTransferSize      extends Field[Int](64)
case object BeatBytes            extends Field[Int](8)
case object IDBits               extends Field[Int](4)
case object AddrBits             extends Field[Int](22)
case object NumTransactions      extends Field[Int](10000)
case object MaxFlight            extends Field[Int](128)
case object NumMemoryChannels    extends Field[Int](1)
case object FuzzerAddressMaskKey extends Field[BigInt]

class WithSlavePortParams
    extends Config((site, _, _) => { case AXI4SlavePort =>
      AXI4SlavePortParameters(
        slaves    = Seq(
          AXI4SlaveParameters(
            address       = Seq(AddressSet(BigInt(0), (BigInt(1) << site(AddrBits)) - 1)),
            regionType    = RegionType.UNCACHED,
            executable    = true,
            supportsWrite = TransferSizes(1, site(MaxTransferSize)),
            supportsRead  = TransferSizes(1, site(MaxTransferSize)),
            interleavedId = Some(0),
          )
        ),
        beatBytes = site(BeatBytes),
      )
    })

class WithDefaultMemPort
    extends Config((site, _, _) => { case ExtMem =>
      Some(
        MemoryPortParams(
          MasterPortParams(
            base      = BigInt(0),
            size      = BigInt(1) << site(AddrBits),
            beatBytes = site(BeatBytes),
            idBits    = site(IDBits),
          ),
          1,
        )
      )
    })

class WithDefaultFuzzer
    extends Config((site, _, _) => {
      case FuzzerAddressMaskKey => (BigInt(1) << site(AddrBits)) - 1
      case FuzzerParametersKey  =>
        Seq(FuzzerParameters(site(NumTransactions), site(MaxFlight), Some(AddressSet(0, site(FuzzerAddressMaskKey)))))
    })

// Configures the total number of transactions generated
class WithNTransactions(num: Int)
    extends Config((_, _, _) => { case NumTransactions =>
      num
    })

class NT10e3 extends WithNTransactions(1000)
class NT10e5 extends WithNTransactions(100000)
class NT10e6 extends WithNTransactions(1000000)
class NT10e7 extends WithNTransactions(10000000)

// Provides an address mask to fuzzer to constrain generated addresses
class WithFuzzerMask(mask: BigInt)
    extends Config((_, _, _) => { case FuzzerAddressMaskKey =>
      mask
    })
class FuzzMask3FFF extends WithFuzzerMask(0x3fff)

// Generates N fuzzers mastering non-overlapping chunks of the target memory space
class WithNFuzzers(numFuzzers: Int)
    extends Config((site, _, _) => { case FuzzerParametersKey =>
      Seq.tabulate(numFuzzers) { i =>
        require(isPow2(numFuzzers))
        val regionSize = (BigInt(1) << site(AddrBits)) / numFuzzers
        FuzzerParameters(
          site(NumTransactions) / numFuzzers,
          site(MaxFlight),
          Some(AddressSet(regionSize * i, (regionSize - 1) & site(FuzzerAddressMaskKey))),
        )
      }
    })
class QuadFuzzer extends WithNFuzzers(4)

// Configures the size of the target memory system
class WithNAddressBits(num: Int)
    extends Config((_, _, _) => { case AddrBits =>
      num
    })
class AddrBits16 extends WithNAddressBits(16)
class AddrBits22 extends WithNAddressBits(22)

// Number of target memory channels -> number of FASED instances
class QuadChannel extends WithNMemoryChannels(4)

/** Complete target configurations
  */

class DefaultConfig
    extends Config(
      new WithoutTLMonitors ++
        new WithDefaultFuzzer ++
        new WithDefaultMemPort ++
        new Config((site, _, _) => { case junctions.NastiKey =>
          NastiParameters(site(BeatBytes) * 8, site(AddrBits), site(IDBits))
        })
    )

class FCFSConfig
    extends Config(
      new FCFS16GBQuadRank ++
        new DefaultConfig
    )

class FRFCFSConfig
    extends Config(
      new FRFCFS16GBQuadRank ++
        new DefaultConfig
    )

class LLCDRAMConfig
    extends Config(
      new FRFCFS16GBQuadRankLLC4MB ++
        new DefaultConfig
    )

/** Host memory system fragments
  */

class WithNHostIdBits(num: Int)
    extends Config((_, _, up) => { case midas.core.HostMemChannelKey =>
      up(midas.core.HostMemChannelKey).copy(idBits = num)
    })

class ConstrainHostIds(idBits: Int)
    extends Config((_, _, _) => { case midas.core.HostMemIdSpaceKey =>
      Some(midas.core.AXI4IdSpaceConstraint(idBits))
    })

/** Complete platform / compiler configurations
  */

class DefaultF1Config
    extends Config(
      new WithDefaultMemModel ++
        new midas.F1Config
    )

class SmallQuadChannelHostConfig
    extends Config(new Config((site, _, _) => {
      case midas.core.HostMemNumChannels => 4
      case midas.core.HostMemChannelKey  =>
        midas.core.HostMemChannelParams(
          size      = (BigInt(1) << site(AddrBits)) / site(midas.core.HostMemNumChannels),
          beatBytes = 8,
          idBits    = 6,
        )
    }) ++ new DefaultF1Config)

class ConstrainedIdHostConfig
    extends Config(
      new ConstrainHostIds(2) ++
        new WithNHostIdBits(2) ++
        new DefaultF1Config
    )
