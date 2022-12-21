//See LICENSE for license details.
package firesim.configs

import firrtl.options.Dependency
import freechips.rocketchip.config.{Parameters, Config, Field}
import midas.{TargetTransforms, HostTransforms}
import midas.widgets.{InsertTokenHashersKey, TokenHashersUseCounter}
import firesim.bridges._

/** Defines a test group with Token Hashers enabled.
  */
class WithTokenHashers extends Config((site, here, up) => {
  case InsertTokenHashersKey => true
  case TokenHashersUseCounter => false
})

/** Defines a test group with Token Hashers enabled, but in counter mode.
  */
class WithTokenHashersCounter extends Config((site, here, up) => {
  case InsertTokenHashersKey => true
  case TokenHashersUseCounter => true
})


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

class WithModelMultiThreading extends Config((site, here, up) => {
  case midas.EnableModelMultiThreading => true
})

// Short name aliases for above
class MCRams extends WithMultiCycleRamModels

class MTModels extends WithModelMultiThreading

// Enables NIC loopback the NIC widget
class WithNICWidgetLoopback  extends Config((site, here, up) => {
  case LoopbackNIC => true
})

// ADDITIONAL TARGET TRANSFORMATIONS
// These run on the the Target FIRRTL before Target-toHost Bridges are extracted ,
// and decoupling is introduced

// Replaces Rocket Chip's black-box async resets with a synchronous equivalent
class WithAsyncResetReplacement extends Config((site, here, up) => {
  case TargetTransforms => Dependency(firesim.passes.AsyncResetRegPass) +: up(TargetTransforms, site)
})

// The wiring transform is normally only run as part of ReplSeqMem
class WithWiringTransform extends Config((site, here, up) => {
  case TargetTransforms => Dependency[firrtl.passes.wiring.WiringTransform] +: up(TargetTransforms, site)
})


// ADDITIONAL HOST TRANSFORMATIONS
// These run on the generated simulator(after all Golden Gate transformations:
// host-decoupling is introduced, and BridgeModules are elaborated)

// Tells ILATopWiringTransform to actually populate the ILA
class WithAutoILA extends Config((site, here, up) => {
  case midas.EnableAutoILA => true
})

// Implements the AutoCounter performace counters features
class WithAutoCounter extends Config((site, here, up) => {
  case midas.EnableAutoCounter => true
})

class WithAutoCounterPrintf extends Config((site, here, up) => {
  case midas.EnableAutoCounter => true
  case midas.AutoCounterUsePrintfImpl => true
  case midas.SynthPrints => true
})

class BaseF1Config extends Config(
  new WithWiringTransform ++
  new WithAsyncResetReplacement ++
  new midas.F1Config
)

class BaseVitisConfig extends Config(
  new WithWiringTransform ++
  new WithAsyncResetReplacement ++
//  new WithEC2F1Artefacts ++
//  new WithILATopWiringTransform ++
  new midas.VitisConfig
)
