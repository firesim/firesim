package firesim.firesim

import freechips.rocketchip.config.{Parameters, Config, Field}

import midas.{EndpointKey}
import midas.widgets.{EndpointMap}
import midas.models._

import testchipip.{WithBlockDevice}

import firesim.endpoints._

object BaseParamsKey extends Field[BaseParams]
object LlcKey extends Field[Option[LLCParams]]
object DramOrganizationKey extends Field[DramOrganizationParams]
object DesiredHostFrequency extends Field[Int](190) // In MHz

object LLCHWSettings extends Field[Option[LLCHardwiredSettings]](None)
object DramHWSettings extends Field[Option[DramHardwiredSettings]](None)

class WithDesiredHostFrequency(freq: Int) extends Config((site, here, up) => {
    case DesiredHostFrequency => freq
})

// Removes default endpoints from the MIDAS-provided config
class BasePlatformConfig extends Config(new Config((site, here, up) => {
    case EndpointKey => EndpointMap(Seq.empty)
}) ++ new midas.F1Config)

// Experimental: mixing this in will enable assertion synthesis
class WithSynthAsserts extends Config((site, here, up) => {
  case midas.SynthAsserts => true
  case EndpointKey => EndpointMap(Seq(new midas.widgets.AssertBundleEndpoint)) ++ up(EndpointKey)
})

// Experimental: mixing this in will enable print synthesis
class WithPrintfSynthesis extends Config((site, here, up) => {
  case midas.SynthPrints => true
  case EndpointKey => EndpointMap(Seq(new midas.widgets.PrintRecordEndpoint)) ++ up(EndpointKey)
})

class WithSerialWidget extends Config((site, here, up) => {
  case EndpointKey => up(EndpointKey) ++ EndpointMap(Seq(new SimSerialIO))
})

class WithUARTWidget extends Config((site, here, up) => {
  case EndpointKey => up(EndpointKey) ++ EndpointMap(Seq(new SimUART))
})

class WithSimpleNICWidget extends Config((site, here, up) => {
  case EndpointKey => up(EndpointKey) ++ EndpointMap(Seq(new SimSimpleNIC))
  case LoopbackNIC => false
})

class WithBlockDevWidget extends Config((site, here, up) => {
  case EndpointKey => up(EndpointKey) ++ EndpointMap(Seq(new SimBlockDev))
})

class WithTracerVWidget extends Config((site, here, up) => {
  case midas.EndpointKey => up(midas.EndpointKey) ++
    EndpointMap(Seq(new SimTracerV))
})

// Instantiates an AXI4 memory model that executes (1 / clockDivision) of the frequency
// of the RTL transformed model (Rocket Chip)
class WithDefaultMemModel(clockDivision: Int = 1) extends Config((site, here, up) => {
  case EndpointKey => up(EndpointKey) ++ EndpointMap(Seq(
    new FASEDAXI4Endpoint(midas.core.ReciprocalClockRatio(clockDivision))))
  case LlcKey => None
  // Only used if a DRAM model is requested
  case DramOrganizationKey => DramOrganizationParams(maxBanks = 8, maxRanks = 4, dramSize = BigInt(1) << 34)
  // Default to a Latency-Bandwidth Pipe without and LLC model
  case BaseParamsKey => new BaseParams(
    maxReads = 16,
    maxWrites = 16,
    beatCounters = true,
    llcKey = site(LlcKey),
    hardWiredSettings = site(DramHWSettings),
    hardWiredLLCSettings = site(LLCHWSettings)
  )

	case MemModelKey => (p: Parameters) => new FASEDMemoryTimingModel(new
		LatencyPipeConfig(site(BaseParamsKey))(p))(p)
})


/*******************************************************************************
* Memory-timing model configuration modifiers
*******************************************************************************/
// Adds a LLC model with at most <maxSets> sets with <maxWays> ways
class WithLLCModel(maxSets: Int, maxWays: Int, maxBlockSize: Int = 128) extends Config((site, here, up) => {
  case LlcKey => Some(LLCParams().copy(
    ways = WRange(1, maxWays),
    sets = WRange(1, maxSets),
    blockBytes = WRange(8, maxBlockSize)
  ))
})

// Changes the default DRAM memory organization.
class WithDramOrganization(maxRanks: Int, maxBanks: Int, dramSize: BigInt)
    extends Config((site, here, up) => {
  case DramOrganizationKey => up(DramOrganizationKey, site).copy(
    maxBanks = maxBanks,
    maxRanks = maxRanks,
    dramSize = dramSize
  )
})


// Instantiates a DDR3 model with a FCFS memory access scheduler
class WithDDR3FIFOMAS(queueDepth: Int) extends Config((site, here, up) => {
  case MemModelKey => (p: Parameters) => new FASEDMemoryTimingModel(
    new FIFOMASConfig(
      transactionQueueDepth = queueDepth,
      dramKey = site(DramOrganizationKey),
      baseParams = site(BaseParamsKey))(p))(p)
})

// Instantiates a DDR3 model with a FR-FCFS memory access scheduler
// windowSize = Maximum number of references the MAS can schedule across
class WithDDR3FRFCFS(windowSize: Int, queueDepth: Int) extends Config((site, here, up) => {
  case MemModelKey => (p: Parameters) => new FASEDMemoryTimingModel(
    new FirstReadyFCFSConfig(
      schedulerWindowSize = windowSize,
      transactionQueueDepth = queueDepth,
      dramKey = site(DramOrganizationKey),
      baseParams = site(BaseParamsKey))(p))(p)
  }
)

// Changes the functional model capacity limits
class WithFuncModelLimits(maxReads: Int, maxWrites: Int) extends Config((site, here, up) => {
  case BaseParamsKey => up(BaseParamsKey, site).copy(
    maxReads = maxReads,
    maxWrites = maxWrites
  )
})

class WithMysteryMemoryModel(
    dramSettings: DramHardwiredSettings,
    llcSettings: LLCHardwiredSettings
  ) extends Config((site, here, up) => {
  case LLCHWSettings => Some(llcSettings)
  case DramHWSettings => Some(dramSettings)
})


/*******************************************************************************
* Complete Memory-Timing Model Configurations
*******************************************************************************/
// Latency Bandwidth Pipes
class LBP32R32W extends Config(
  new WithFuncModelLimits(32,32) ++
  new WithDefaultMemModel)
class LBP32R32WLLC4MB extends Config(
  new WithLLCModel(4096, 8) ++
  new WithFuncModelLimits(32,32) ++
  new WithDefaultMemModel)

// An LBP that runs at 1/3 the frequency of the cores + uncore
// This is 1067 MHz for default core frequency of 3.2 GHz
class LBP32R32W3Div extends Config(
  new WithFuncModelLimits(32,32) ++
  new WithDefaultMemModel(3))

// DDR3 - FCFS models.
class FCFS16GBQuadRank extends Config(new WithDDR3FIFOMAS(8) ++ new FireSimConfig)
class FCFS16GBQuadRankLLC4MB extends Config(
  new WithLLCModel(4096, 32, 1024) ++
  new FCFS16GBQuadRank)

// DDR3 - First-Ready FCFS models
class FRFCFS16GBQuadRank(clockDiv: Int = 1) extends Config(
  new WithFuncModelLimits(32,32) ++
  new WithDDR3FRFCFS(8, 8) ++
  new WithDefaultMemModel(clockDiv)
)
class FRFCFS16GBQuadRankLLC4MB extends Config(
  new WithLLCModel(4096, 8) ++
  new FRFCFS16GBQuadRank
)

class FRFCFS16GBQuadRankLLC4MB3Div extends Config(
  new WithLLCModel(4096, 8) ++
  new FRFCFS16GBQuadRank(3)
)

/*******************************************************************************
* Full PLATFORM_CONFIG Configurations. These set simulator parameters.
*
* In general, if you're adding or removing features from any of these, you
* should CREATE A NEW ONE, WITH A NEW NAME. This is because the manager
* will store this name as part of the tags for the AGFI, so that later you can
* reconstruct what is in a particular AGFI. These tags are also used to
* determine which driver to build.
*******************************************************************************/
class FireSimConfig extends Config(
  new WithDesiredHostFrequency(90) ++
  new WithSerialWidget ++
  new WithUARTWidget ++
  new WithSimpleNICWidget ++
  new WithBlockDevWidget ++
  new WithDefaultMemModel ++
  new WithTracerVWidget ++
  new BasePlatformConfig)

class FireSimConfig160MHz extends Config(
  new WithDesiredHostFrequency(160) ++
  new FireSimConfig)

class FireSimConfig90MHz extends Config(
  new WithDesiredHostFrequency(90) ++
  new FireSimConfig)

class FireSimConfig75MHz extends Config(
  new WithDesiredHostFrequency(75) ++
  new FireSimConfig)

class FireSimClockDivConfig extends Config(
  new WithDesiredHostFrequency(90) ++
  new WithSerialWidget ++
  new WithUARTWidget ++
  new WithSimpleNICWidget ++
  new WithBlockDevWidget ++
  new WithDefaultMemModel(clockDivision = 2) ++
  new BasePlatformConfig)

class FireSimDDR3Config extends Config(
  new WithDesiredHostFrequency(90) ++
  new WithSerialWidget ++
  new WithUARTWidget ++
  new WithSimpleNICWidget ++
  new WithBlockDevWidget ++
  new FCFS16GBQuadRank ++
  new BasePlatformConfig)

class FireSimDDR3LLC4MBConfig extends Config(
  new WithDesiredHostFrequency(90) ++
  new WithSerialWidget ++
  new WithUARTWidget ++
  new WithSimpleNICWidget ++
  new WithBlockDevWidget ++
  new FCFS16GBQuadRankLLC4MB ++
  new BasePlatformConfig)

class FireSimDDR3FRFCFSConfig extends Config(
  new WithDesiredHostFrequency(90) ++
  new WithSerialWidget ++
  new WithUARTWidget ++
  new WithSimpleNICWidget ++
  new WithBlockDevWidget ++
  new FRFCFS16GBQuadRank ++
  new BasePlatformConfig)

class FireSimDDR3FRFCFSLLC4MBConfig extends Config(
  new WithDesiredHostFrequency(90) ++
  new WithSerialWidget ++
  new WithUARTWidget ++
  new WithSimpleNICWidget ++
  new WithBlockDevWidget ++
  new FRFCFS16GBQuadRankLLC4MB ++
  new BasePlatformConfig)

class FireSimDDR3FRFCFSLLC4MBConfig160MHz extends Config(
  new WithDesiredHostFrequency(160) ++
  new FireSimDDR3FRFCFSLLC4MBConfig)

class FireSimDDR3FRFCFSLLC4MBConfig90MHz extends Config(
  new WithDesiredHostFrequency(90) ++
  new FireSimDDR3FRFCFSLLC4MBConfig)

class FireSimDDR3FRFCFSLLC4MBConfig75MHz extends Config(
  new WithDesiredHostFrequency(75) ++
  new FireSimDDR3FRFCFSLLC4MBConfig)

class FireSimDDR3FRFCFSLLC4MB3ClockDivConfig extends Config(
  new WithDesiredHostFrequency(90) ++
  new WithSerialWidget ++
  new WithUARTWidget ++
  new WithSimpleNICWidget ++
  new WithBlockDevWidget ++
  new FRFCFS16GBQuadRankLLC4MB3Div ++
  new BasePlatformConfig)

/*******************************************************************************
* CS152 Configs
*******************************************************************************/
class LBPBaseConfig extends FireSimConfig160MHz
class DRAMBaseConfig extends Config(new WithDesiredHostFrequency(160) ++ new FireSimDDR3Config)
class LLCDRAMBaseConfig extends Config(new WithDesiredHostFrequency(160) ++ new FireSimDDR3LLC4MBConfig)
class LLCFRFCFSBaseConfig extends Config(new WithDesiredHostFrequency(160) ++ new FireSimDDR3FRFCFSConfig)
class ToyPlatformConfig extends Config(
  new WithDesiredHostFrequency(75) ++
  new WithLLCModel(0x8000, 64) ++ // 128M capacity with 64 byte lines, 65 ways
  new FireSimDDR3LLC4MBConfig)

class TestMysteryConfig extends Config(
  new WithMysteryMemoryModel(
    DramHardwiredSettings(
      pagePolicy = true,
      addrAssignment = AddressAssignmentSetting(
        bankAddrMask = 7,
        bankAddrOffset = 13,
        rankAddrMask = 3,
        rankAddrOffset = 16,
        rowAddrOffset = 65535,
        rowAddrMask = 18),
      timings = Seq( 0, 14, 1, 10, 4, 25, 33, 7800, 47, 14, 260, 5, 14, 8, 2, 15, 8)),
    LLCHardwiredSettings(
      wayBits = 2,
      setBits = 12,
      blockBits = 6) 
    ) ++ new LLCDRAMBaseConfig
)

class MysteryConfig0 extends Config(
  new WithMysteryMemoryModel(
    DramHardwiredSettings(
      pagePolicy = true,
      addrAssignment = AddressAssignmentSetting(
        bankAddrMask = 7,
        bankAddrOffset = 13,
        rankAddrMask = 3,
        rankAddrOffset = 16,
        rowAddrMask = (1 << 16) - 1,
        rowAddrOffset = 18),
      timings = Seq( 0, 14, 1, 10, 4, 25, 33, 7800, 47, 14, 260, 5, 14, 8, 2, 15, 8)),
    LLCHardwiredSettings(
      wayBits = 3,
      setBits = 10,
      blockBits = 6) /// 512
    ) ++ new LLCDRAMBaseConfig
)

class MysteryConfig1 extends Config(
  new WithMysteryMemoryModel(
    DramHardwiredSettings(
      pagePolicy = true,
      addrAssignment = AddressAssignmentSetting(
        bankAddrMask = 7,
        bankAddrOffset = 14,
        rankAddrMask = 0,
        rankAddrOffset = 16,
        rowAddrMask = (1 << 17) - 1,
        rowAddrOffset = 17),
        //DDR3-1066
      timings = Seq(0, 8, 1, 6, 4, 19, 19, 3900, 27, 8, 130, 4, 8, 4, 2, 8, 4)),
    LLCHardwiredSettings(
      wayBits = 2,
      setBits = 8,
      blockBits = 6) // 64K
    ) ++ new LLCDRAMBaseConfig
)

class MysteryConfig2 extends Config(
  new WithMysteryMemoryModel(
    DramHardwiredSettings(
      pagePolicy = false,
      addrAssignment = AddressAssignmentSetting(
        bankAddrMask = 7,
        bankAddrOffset = 6,
        rankAddrMask = 3,
        rankAddrOffset = 9,
        rowAddrMask = (1 << 16) - 1,
        rowAddrOffset = 18),
      // DDR3-160// 512K
      timings = Seq(0, 11, 1, 8, 4, 24, 28, 6240, 39, 11, 208, 5, 11, 6, 2, 12, 6)),
    LLCHardwiredSettings(
      wayBits = 3,
      setBits = 12,
      blockBits = 6) // 2M
    ) ++ new LLCDRAMBaseConfig
)

// Frequency configs
class F90MHz extends WithDesiredHostFrequency(90)
