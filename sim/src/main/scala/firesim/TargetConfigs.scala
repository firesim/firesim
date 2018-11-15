package firesim.firesim

import chisel3._
import freechips.rocketchip.config.{Parameters, Config}
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.devices.tilelink.BootROMParams
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

class WithLargeTLBs extends Config((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey) map (tile => tile.copy(
    icache = tile.icache map (_.copy(
      nTLBEntries = 32 // TLB reach = 32 * 4KB = 128KB
    )),
    dcache = tile.dcache map (_.copy(
      nTLBEntries = 32 // TLB reach = 32 * 4KB = 128KB
    )),
    core = tile.core.copy(
      nL2TLBEntries = 1024 // TLB reach = 1024 * 4KB = 4MB
    )
  ))
})

class WithPerfCounters extends Config((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey) map (tile => tile.copy(
    core = tile.core.copy(nPerfCounters = 29)
  ))
})

class BoomWithLargeTLBs extends Config((site, here, up) => {
  case BoomTilesKey => up(BoomTilesKey) map (tile => tile.copy(
    core = tile.core.copy(
      nL2TLBEntries = 1024 // TLB reach = 1024 * 4KB = 4MB
    )
  ))
})

class WithTraceRocket extends Config((site, here, up) => {
   case RocketTilesKey => up(RocketTilesKey, site) map { r => r.copy(trace = true) }
})

class WithTraceBoom extends Config((site, here, up) => {
   case BoomTilesKey => up(BoomTilesKey, site) map { r => r.copy(trace = true) }
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
  new WithLargeTLBs ++
  new WithPerfCounters ++
  new freechips.rocketchip.system.DefaultConfig)

class FireSimRocketChipTracedConfig extends Config(
  new WithTraceRocket ++ new FireSimRocketChipConfig)

// single core config
class FireSimRocketChipSingleCoreConfig extends Config(new FireSimRocketChipConfig)

class FireSimRocketChipSingleCoreTracedConfig extends Config(
  new WithTraceRocket ++ new FireSimRocketChipSingleCoreConfig)

// dual core config
class FireSimRocketChipDualCoreConfig extends Config(
  new WithNBigCores(2) ++
  new FireSimRocketChipSingleCoreConfig)

class FireSimRocketChipDualCoreTracedConfig extends Config(
  new WithTraceRocket ++ new FireSimRocketChipDualCoreConfig)

// quad core config
class FireSimRocketChipQuadCoreConfig extends Config(
  new WithNBigCores(4) ++
  new FireSimRocketChipSingleCoreConfig)

class FireSimRocketChipQuadCoreTracedConfig extends Config(
  new WithTraceRocket ++ new FireSimRocketChipQuadCoreConfig)

// hexa core config
class FireSimRocketChipHexaCoreConfig extends Config(
  new WithNBigCores(6) ++
  new FireSimRocketChipSingleCoreConfig)

class FireSimRocketChipHexaCoreTracedConfig extends Config(
  new WithTraceRocket ++ new FireSimRocketChipHexaCoreConfig)

// octa core config
class FireSimRocketChipOctaCoreConfig extends Config(
  new WithNBigCores(8) ++
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

class FireSimBoomConfig extends Config(
  new WithBootROM ++
  new WithPeripheryBusFrequency(BigInt(3200000000L)) ++
  new WithExtMemSize(0x400000000L) ++ // 16GB
  new WithoutTLMonitors ++
  new WithUARTKey ++
  new WithNICKey ++
  new WithBlockDevice ++
  new BoomWithLargeTLBs ++
  // Using a small config because it has 64-bit system bus, and compiles quickly
  new boom.system.SmallBoomConfig)

class FireSimBoomTracedConfig extends Config(
  new WithTraceBoom ++ new FireSimBoomConfig)
