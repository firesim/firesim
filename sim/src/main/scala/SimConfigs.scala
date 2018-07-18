package firesim

import endpoints.LoopbackNIC
import midas.models._
import midas.core.{SimAXI4MemIO, ReciprocalClockRatio, EndpointMap}
import midas.{EndpointKey, MemModelKey}
import testchipip.{WithBlockDevice}
import freechips.rocketchip.config.{Parameters, Config, Field}


object BaseParamsKey extends Field[BaseParams]
object LlcKey extends Field[Option[LLCParams]]
object DramOrganizationKey extends Field[DramOrganizationParams]

// Removes default endpoints from the MIDAS-provided config
class BasePlatformConfig extends Config(new Config((site, here, up) => {
    case EndpointKey => EndpointMap(Seq.empty)
}) ++ new midas.F1Config)

class WithSerialWidget extends Config((site, here, up) => {
  case EndpointKey => up(EndpointKey) ++ EndpointMap(Seq(new endpoints.SimSerialIO))
})

class WithUARTWidget extends Config((site, here, up) => {
  case EndpointKey => up(EndpointKey) ++ EndpointMap(Seq(new endpoints.SimUART))
})

class WithSimpleNICWidget extends Config((site, here, up) => {
  case EndpointKey => up(EndpointKey) ++ EndpointMap(Seq(new endpoints.SimSimpleNIC))
  case LoopbackNIC => false
})

class WithBlockDevWidget extends Config((site, here, up) => {
  case EndpointKey => up(EndpointKey) ++ EndpointMap(Seq(new endpoints.SimBlockDev))
})

// Instantiates an AXI4 memory model that executes (1 / clockDivision) of the frequency
// of the RTL transformed model (Rocket Chip)
class WithDefaultMemModel(clockDivision: Int = 1) extends Config((site, here, up) => {
  case EndpointKey => up(EndpointKey) ++ EndpointMap(Seq(
    new SimAXI4MemIO(ReciprocalClockRatio(clockDivision))))
  case LlcKey => None
  // Only used if a DRAM model is requested
  case DramOrganizationKey => DramOrganizationParams(maxBanks = 8, maxRanks = 4, dramSize = BigInt(1) << 34)
  // Default to a Latency-Bandwidth Pipe without and LLC model
  case BaseParamsKey => new BaseParams(
    maxReads = 16,
    maxWrites = 16,
    maxReadLength = 8,
    maxWriteLength = 8,
    beatCounters = true,
    llcKey = site(LlcKey))

	case MemModelKey => Some((p: Parameters) => new MidasMemModel(new
		LatencyPipeConfig(site(BaseParamsKey)))(p))
})


/*******************************************************************************
* Memory-timing model configuration modifiers
*******************************************************************************/
// Adds a LLC model with at most <maxSets> sets with <maxWays> ways
class WithLLCModel(maxSets: Int, maxWays: Int) extends Config((site, here, up) => {
  case LlcKey => Some(LLCParams().copy(
    ways = WRange(1, maxWays),
    sets = WRange(1, maxSets)
  ))
})

// Changes the default DRAM memory organization.
class WithDramOrganization(maxRanks: Int, maxBanks: Int, dramSize: BigInt)
    extends Config((site, here, up) => {
  case DramOrganizationKey => site(DramOrganizationKey).copy(
    maxBanks = maxBanks,
    maxRanks = maxRanks,
    dramSize = dramSize
  )
})


// Instantiates a DDR3 model with a FCFS memory access scheduler
class WithDDR3FIFOMAS(queueDepth: Int) extends Config((site, here, up) => {
  case MemModelKey => Some((p: Parameters) => new MidasMemModel(
    new FIFOMASConfig(
      transactionQueueDepth = queueDepth,
      dramKey = site(DramOrganizationKey),
      baseParams = site(BaseParamsKey)))(p))
})

// Instantiates a DDR3 model with a FR-FCFS memory access scheduler
// windowSize = Maximum number of references the MAS can schedule across
class WithDDR3FRFCFS(windowSize: Int, queueDepth: Int) extends Config((site, here, up) => {
  case MemModelKey => Some((p: Parameters) => new MidasMemModel(
    new FirstReadyFCFSConfig(
      schedulerWindowSize = windowSize,
      transactionQueueDepth = queueDepth,
      dramKey = site(DramOrganizationKey),
      baseParams = site(BaseParamsKey)))(p))
  }
)

// Changes the functional model capacity limits
class WithFuncModelLimits(maxReads: Int, maxWrites: Int) extends Config((site, here, up) => {
  case BaseParamsKey => up(BaseParamsKey).copy(
    maxReads = maxReads,
    maxWrites = maxWrites
  )
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

// An LBP that runs at half the frequency of the cores + uncore
class LBP32R32WHalfRate extends Config(
  new WithFuncModelLimits(32,32) ++
  new WithDefaultMemModel(2))

// DDR3 - FCFS models.
class FCFS16GBQuadRank extends Config(new WithDDR3FIFOMAS(8) ++ new FireSimConfig)
class FCFS16GBQuadRankLLC4MB extends Config(
  new WithLLCModel(4096, 8) ++
  new FCFS16GBQuadRank)

// DDR3 - First-Ready FCFS models
class FRFCFS16GBQuadRank(clockDiv: Int = 1) extends Config(
  new WithDDR3FRFCFS(8, 8) ++
  new WithDefaultMemModel(clockDiv)
)
class FRFCFS16GBQuadRankLLC4MB extends Config(
  new WithLLCModel(4096, 8) ++
  new FRFCFS16GBQuadRank
)

// DDR3 - FRFCFS models include a clock division between uncore and DDR controller (1/2 and 1/4)
class FRFCFS16GBQuadRankLLC4MBHalfRate extends Config(
  new WithLLCModel(4096, 8) ++
  new FRFCFS16GBQuadRank(2)
)

class FRFCFS16GBQuadRankLLC4MBQuarterRate extends Config(
  new WithLLCModel(4096, 8) ++
  new FRFCFS16GBQuadRank(4)
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
  new WithSerialWidget ++
  new WithUARTWidget ++
  new WithSimpleNICWidget ++
  new WithBlockDevWidget ++
  new WithDefaultMemModel ++
  new BasePlatformConfig)

class FireSimHalfRateLBPConfig extends Config(
  new WithSerialWidget ++
  new WithUARTWidget ++
  new WithSimpleNICWidget ++
  new WithBlockDevWidget ++
  new LBP32R32WHalfRate ++
  new BasePlatformConfig)

class FireSimDDR3Config extends Config(
  new WithSerialWidget ++
  new WithUARTWidget ++
  new WithSimpleNICWidget ++
  new WithBlockDevWidget ++
  new FCFS16GBQuadRank ++
  new BasePlatformConfig)

class FireSimDDR3LLC4MBConfig extends Config(
  new WithSerialWidget ++
  new WithUARTWidget ++
  new WithSimpleNICWidget ++
  new WithBlockDevWidget ++
  new FCFS16GBQuadRankLLC4MB ++
  new BasePlatformConfig)

class FireSimDDR3FRFCFSConfig extends Config(
  new WithSerialWidget ++
  new WithUARTWidget ++
  new WithSimpleNICWidget ++
  new WithBlockDevWidget ++
  new FRFCFS16GBQuadRank ++
  new BasePlatformConfig)

class FireSimDDR3FRFCFSLLC4MBConfig extends Config(
  new WithSerialWidget ++
  new WithUARTWidget ++
  new WithSimpleNICWidget ++
  new WithBlockDevWidget ++
  new FRFCFS16GBQuadRankLLC4MB ++
  new BasePlatformConfig)
