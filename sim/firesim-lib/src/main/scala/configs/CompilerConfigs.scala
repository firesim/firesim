//See LICENSE for license details.
package firesim.configs

import freechips.rocketchip.config.{Parameters, Config, Field}
import midas.{TargetTransforms, HostTransforms}
import firesim.bridges._

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

// ADDITIONAL TARGET TRANSFORMATIONS
// These run on the the Target FIRRTL before Target-toHost Bridges are extracted ,
// and decoupling is introduced

// Replaces Rocket Chip's black-box async resets with a synchronous equivalent
class WithAsyncResetReplacement extends Config((site, here, up) => {
  case TargetTransforms => ((p: Parameters) => Seq(firesim.passes.AsyncResetRegPass)) +: up(TargetTransforms, site)
})

class WithPlusArgReaderRemoval extends Config((site, here, up) => {
  case TargetTransforms => ((p: Parameters) => Seq(firesim.passes.PlusArgReaderPass)) +: up(TargetTransforms, site)
})

// ADDITIONAL HOST TRANSFORMATIONS
// These run on the generated simulator(after all Golden Gate transformations:
// host-decoupling is introduced, and BridgeModules are elaborated)

// Generates additional TCL scripts requried by FireSim's EC2 F1 vivado flow
class WithEC2F1Artefacts extends Config((site, here, up) => {
    case HostTransforms => ((p: Parameters) => Seq(new firesim.passes.EC2F1Artefacts()(p))) +: up(HostTransforms, site)
})

// Implements the AutoILA feature on EC2 F1
class WithILATopWiringTransform extends Config((site, here, up) => {
  case HostTransforms => ((p: Parameters) => Seq(new firesim.passes.ILATopWiringTransform)) +: up(HostTransforms, site)
})

// Implements the AutoCounter performace counters features
class WithAutoCounter extends Config((site, here, up) => {
  case midas.TraceTrigger => true
  case TargetTransforms => ((p: Parameters) => Seq(new midas.passes.AutoCounterTransform()(p))) +: up(TargetTransforms, site)
})

class WithAutoCounterPrintf extends Config((site, here, up) => {
  case midas.SynthPrints => true
  case TargetTransforms => ((p: Parameters) => Seq(new midas.passes.AutoCounterTransform(printcounter = true)(p))) +: up(TargetTransforms, site)
})

class BaseF1Config extends Config(
  new WithAsyncResetReplacement ++
  new WithPlusArgReaderRemoval ++
  new WithEC2F1Artefacts ++
  new WithILATopWiringTransform ++
  new midas.F1Config
)
