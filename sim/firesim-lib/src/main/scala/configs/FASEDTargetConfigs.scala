//See LICENSE for license details.
package firesim.configs

import freechips.rocketchip.config.{Config, Field}

import midas.models._

case object MemModelKey extends Field[BaseConfig]
case object BaseParamsKey extends Field[BaseParams]
case object LlcKey extends Field[Option[LLCParams]]
case object DramOrganizationKey extends Field[DramOrganizationParams]

// Instantiates an AXI4 memory model that executes (1 / clockDivision) of the frequency
// of the RTL transformed model (Rocket Chip)
class WithDefaultMemModel extends Config((site, here, up) => {
  case LlcKey => None
  // Only used if a DRAM model is requested
  case DramOrganizationKey => DramOrganizationParams(maxBanks = 8, maxRanks = 4, dramSize = BigInt(1) << 34)
  // Default to a Latency-Bandwidth Pipe without and LLC model
  case BaseParamsKey => BaseParams(
    maxReads = 16,
    maxWrites = 16,
    beatCounters = true,
    llcKey = site(LlcKey))

  case MemModelKey => new LatencyPipeConfig(site(BaseParamsKey))
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
  case DramOrganizationKey => up(DramOrganizationKey, site).copy(
    maxBanks = maxBanks,
    maxRanks = maxRanks,
    dramSize = dramSize
  )
})


// Instantiates a DDR3 model with a FCFS memory access scheduler
class WithDDR3FIFOMAS(queueDepth: Int) extends Config((site, here, up) => {
  case MemModelKey => new FIFOMASConfig(
    transactionQueueDepth = queueDepth,
    dramKey = site(DramOrganizationKey),
    params = site(BaseParamsKey))
})

// Instantiates a DDR3 model with a FR-FCFS memory access scheduler
// windowSize = Maximum number of references the MAS can schedule across
class WithDDR3FRFCFS(windowSize: Int, queueDepth: Int) extends Config((site, here, up) => {
  case MemModelKey => new FirstReadyFCFSConfig(
    schedulerWindowSize = windowSize,
    transactionQueueDepth = queueDepth,
    dramKey = site(DramOrganizationKey),
    params = site(BaseParamsKey))
  }
)

// Changes the functional model capacity limits
class WithFuncModelLimits(maxReads: Int, maxWrites: Int) extends Config((site, here, up) => {
  case BaseParamsKey => up(BaseParamsKey, site).copy(
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

// DDR3 - FCFS models.
class FCFS16GBQuadRank extends Config(new WithDDR3FIFOMAS(8) ++ new WithDefaultMemModel)
class FCFS16GBQuadRankLLC4MB extends Config(
  new WithLLCModel(4096, 8) ++
  new FCFS16GBQuadRank)

// DDR3 - First-Ready FCFS models
class FRFCFS16GBQuadRank extends Config(
  new WithFuncModelLimits(32,32) ++
  new WithDDR3FRFCFS(8, 8) ++
  new WithDefaultMemModel
)
class FRFCFS16GBQuadRankLLC4MB extends Config(
  new WithLLCModel(4096, 8) ++
  new FRFCFS16GBQuadRank
)
