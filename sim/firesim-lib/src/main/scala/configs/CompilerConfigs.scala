//See LICENSE for license details.
package firesim.configs

import freechips.rocketchip.config.{Parameters, Config, Field}

import firesim.endpoints._

class BaseF1Config extends Config(new midas.F1Config)

// Experimental: mixing this in will enable assertion synthesis
class WithSynthAsserts extends Config((site, here, up) => {
  case midas.SynthAsserts => true
})

// Experimental: mixing this in will enable print synthesis
class WithPrintfSynthesis extends Config((site, here, up) => {
  case midas.SynthPrints => true
})

// MIDAS 2.0 Switches
class WithMultiCycleRamModels extends Config((site, here, up) => {
  case midas.GenerateMultiCycleRamModels => true
})

// Short name alias for above
class MCRams extends WithMultiCycleRamModels

// Enables NIC loopback the NIC widget
class WithNICWidgetLoopback  extends Config((site, here, up) => {
  case LoopbackNIC => true
})
