package firesim.firesim

import chisel3._
import freechips.rocketchip.config.{Parameters, Config}
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.devices.tilelink.BootROMParams
import freechips.rocketchip.pfa.HasPFA
import boom.system.BoomTilesKey
import testchipip.{WithBlockDevice, BlockDeviceKey, BlockDeviceConfig}
import sifive.blocks.devices.uart.{PeripheryUARTKey, UARTParams}
import icenet._
import memblade.{MemBladeKey, MemBladeParams, MemBladeQueueParams, RemoteMemClientKey, RemoteMemClientConfig}
import memblade.RemoteMemConsts.RMEM_REQ_ETH_TYPE

class WithBootROM extends Config((site, here, up) => {
  case BootROMParams => BootROMParams(
    contentFileName = s"./target-rtl/firechip/testchipip/bootrom/bootrom.rv${site(XLen)}.img")
})

class WithPeripheryBusFrequency(freq: BigInt) extends Config((site, here, up) => {
  case PeripheryBusKey => up(PeripheryBusKey).copy(frequency=freq)
})

class WithUARTKey extends Config((site, here, up) => {
   case PeripheryUARTKey => List(UARTParams(
     address = BigInt(0x54000000L),
     nTxEntries = 256,
     nRxEntries = 256))
})

class WithNICKey extends Config((site, here, up) => {
  case NICKey => NICConfig(
    inBufPackets = 64,
    ctrlQueueDepth = 64)
})

class WithMemBladeKey extends Config((site, here, up) => {
  case MemBladeKey => MemBladeParams(
    spanBytes = 1024,
    nSpanTrackers = 2,
    nWordTrackers = 4,
    spanQueue = MemBladeQueueParams(reqHeadDepth = 64),
    wordQueue = MemBladeQueueParams(reqHeadDepth = 64))
})

class WithRemoteMemClientKey extends Config((site, here, up) => {
  case RemoteMemClientKey => RemoteMemClientConfig(
    spanBytes = site(MemBladeKey).spanBytes,
    nRMemXacts = 64)
})

class WithPFA extends Config((site, here, up) => {
  case HasPFA => true
})

class WithRocketL2TLBs(entries: Int) extends Config((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey) map (tile => tile.copy(
    core = tile.core.copy(
      nL2TLBEntries = entries
    )
  ))
})

class WithPerfCounters extends Config((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey) map (tile => tile.copy(
    core = tile.core.copy(nPerfCounters = 29)
  ))
})

class WithBoomL2TLBs(entries: Int) extends Config((site, here, up) => {
  case BoomTilesKey => up(BoomTilesKey) map (tile => tile.copy(
    core = tile.core.copy(nL2TLBEntries = entries)
  ))
})

class WithTraceRocket extends Config((site, here, up) => {
   case RocketTilesKey => up(RocketTilesKey, site) map { r => r.copy(trace = true) }
})

class WithTraceBoom extends Config((site, here, up) => {
   case BoomTilesKey => up(BoomTilesKey, site) map { r => r.copy(trace = true) }
})

// This is strictly speakig a MIDAS config, but it's target dependent -> mix in to target config
class WithBoomSynthAssertExcludes extends Config((site, here, up) => {
  case midas.ExcludeInstanceAsserts => Seq(
    // Boom instantiates duplicates of these module(s) with the expectation
    // the backend tool will optimize them away. FIXME.
    ("NonBlockingDCache", "dtlb"))
})

/*******************************************************************************
* Full TARGET_CONFIG configurations. These set parameters of the target being
* simulated.
*
* In general, if you're adding or removing features from any of these, you
* should CREATE A NEW ONE, WITH A NEW NAME. This is because the manager
* will store this name as part of the tags for the AGFI, so that later you can
* reconstruct what is in a particular AGFI. These tags are also used to
* determine which driver to build.
*******************************************************************************/
class FireSimRocketChipConfig extends Config(
  new WithBootROM ++
  new WithPeripheryBusFrequency(BigInt(3200000000L)) ++
  new WithExtMemSize(0x400000000L) ++ // 16GB
  new WithoutTLMonitors ++
  new WithUARTKey ++
  new WithNICKey ++
  new WithBlockDevice ++
  new WithRocketL2TLBs(1024) ++
  new WithPerfCounters ++
  new freechips.rocketchip.system.DefaultConfig)

class WithNDuplicatedRocketCores(n: Int) extends Config((site, here, up) => {
  case RocketTilesKey => List.tabulate(n)(i => up(RocketTilesKey).head.copy(hartId = i))
})

class FireSimRocketChipTracedConfig extends Config(
  new WithTraceRocket ++ new FireSimRocketChipConfig)

// single core config
class FireSimRocketChipSingleCoreConfig extends Config(new FireSimRocketChipConfig)

class FireSimRocketChipSingleCoreTracedConfig extends Config(
  new WithTraceRocket ++ new FireSimRocketChipSingleCoreConfig)

// dual core config
class FireSimRocketChipDualCoreConfig extends Config(
  new WithNDuplicatedRocketCores(2) ++
  new FireSimRocketChipSingleCoreConfig)

class FireSimRocketChipDualCoreTracedConfig extends Config(
  new WithTraceRocket ++ new FireSimRocketChipDualCoreConfig)

// quad core config
class FireSimRocketChipQuadCoreConfig extends Config(
  new WithNDuplicatedRocketCores(4) ++
  new FireSimRocketChipSingleCoreConfig)

class FireSimRocketChipQuadCoreTracedConfig extends Config(
  new WithTraceRocket ++ new FireSimRocketChipQuadCoreConfig)

// hexa core config
class FireSimRocketChipHexaCoreConfig extends Config(
  new WithNDuplicatedRocketCores(6) ++
  new FireSimRocketChipSingleCoreConfig)

class FireSimRocketChipHexaCoreTracedConfig extends Config(
  new WithTraceRocket ++ new FireSimRocketChipHexaCoreConfig)

// octa core config
class FireSimRocketChipOctaCoreConfig extends Config(
  new WithNDuplicatedRocketCores(8) ++
  new FireSimRocketChipSingleCoreConfig)

class FireSimRocketChipOctaCoreTracedConfig extends Config(
  new WithTraceRocket ++ new FireSimRocketChipOctaCoreConfig)

class FireSimMemBladeConfig extends Config(
  new WithMemBladeKey ++ new WithRemoteMemClientKey ++ new FireSimRocketChipConfig)

class FireSimMemBladeSingleCoreConfig extends Config(
  new WithNBigCores(1) ++ new FireSimMemBladeConfig)

class FireSimMemBladeDualCoreConfig extends Config(
  new WithNBigCores(2) ++ new FireSimMemBladeConfig)

class FireSimMemBladeQuadCoreConfig extends Config(
  new WithNBigCores(4) ++ new FireSimMemBladeConfig)

class FireSimMemBladePFAConfig extends Config(
  new WithPFA ++ new FireSimMemBladeConfig)

class FireSimMemBladePFASingleCoreConfig extends Config(
  new WithNBigCores(1) ++ new FireSimMemBladePFAConfig)

class FireSimMemBladePFADualCoreConfig extends Config(
  new WithNBigCores(2) ++ new FireSimMemBladePFAConfig)

class FireSimMemBladePFAQuadCoreConfig extends Config(
  new WithNBigCores(4) ++ new FireSimMemBladePFAConfig)

class FireSimBoomConfig extends Config(
  new WithBootROM ++
  new WithPeripheryBusFrequency(BigInt(3200000000L)) ++
  new WithExtMemSize(0x400000000L) ++ // 16GB
  new WithoutTLMonitors ++
  new WithUARTKey ++
  new WithNICKey ++
  new WithBlockDevice ++
  new WithBoomL2TLBs(1024) ++
  new WithBoomSynthAssertExcludes ++ // Will do nothing unless assertion synth is enabled
  // Using a small config because it has 64-bit system bus, and compiles quickly
  new boom.system.SmallBoomConfig)

// A safer implementation than the one in BOOM in that it
// duplicates whatever BOOMTileKey.head is present N times. This prevents
// accidentally (and silently) blowing away configurations that may change the
// tile in the "up" view
class WithNDuplicatedBoomCores(n: Int) extends Config((site, here, up) => {
  case BoomTilesKey => List.tabulate(n)(i => up(BoomTilesKey).head.copy(hartId = i))
})

class FireSimBoomDualCoreConfig extends Config(
  new WithNDuplicatedBoomCores(2) ++
  new FireSimBoomConfig)

class FireSimBoomTracedConfig extends Config(
  new WithTraceBoom ++ new FireSimBoomConfig)


//**********************************************************************************
//* Supernode Configurations
//*********************************************************************************/
class WithNumNodes(n: Int) extends Config((pname, site, here) => {
  case NumNodes => n
})

class SupernodeFireSimRocketChipConfig extends Config(
  new WithNumNodes(4) ++
  new WithExtMemSize(0x200000000L) ++ // 8GB
  new FireSimRocketChipConfig)

class SupernodeFireSimRocketChipSingleCoreConfig extends Config(
  new WithNumNodes(4) ++
  new WithExtMemSize(0x200000000L) ++ // 8GB
  new FireSimRocketChipSingleCoreConfig)

class SupernodeFireSimRocketChipDualCoreConfig extends Config(
  new WithNumNodes(4) ++
  new WithExtMemSize(0x200000000L) ++ // 8GB
  new FireSimRocketChipDualCoreConfig)

class SupernodeFireSimRocketChipQuadCoreConfig extends Config(
  new WithNumNodes(4) ++
  new WithExtMemSize(0x200000000L) ++ // 8GB
  new FireSimRocketChipQuadCoreConfig)

class SupernodeFireSimRocketChipHexaCoreConfig extends Config(
  new WithNumNodes(4) ++
  new WithExtMemSize(0x200000000L) ++ // 8GB
  new FireSimRocketChipHexaCoreConfig)

class SupernodeFireSimRocketChipOctaCoreConfig extends Config(
  new WithNumNodes(4) ++
  new WithExtMemSize(0x200000000L) ++ // 8GB
  new FireSimRocketChipOctaCoreConfig)

