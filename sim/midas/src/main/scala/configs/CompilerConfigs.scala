//See LICENSE for license details.
package firesim.configs

import firrtl.options.Dependency
import org.chipsalliance.cde.config.{Config, Parameters}
import midas.TargetTransforms

// Experimental: mixing this in will enable assertion synthesis
class WithSynthAsserts
    extends Config((_, _, _) => { case midas.SynthAsserts =>
      true
    })

// Experimental: mixing this in will enable print synthesis
class WithPrintfSynthesis
    extends Config((_, _, _) => { case midas.SynthPrints =>
      true
    })

// MIDAS 2.0 Switches
class WithMultiCycleRamModels
    extends Config((_, _, _) => { case midas.GenerateMultiCycleRamModels =>
      true
    })

class WithModelMultiThreading
    extends Config((_, _, _) => { case midas.EnableModelMultiThreading =>
      true
    })

// Short name aliases for above
class MCRams extends WithMultiCycleRamModels

class MTModels extends WithModelMultiThreading

class WithILADepth(depth: Int)
    extends Config((_, _, _) => { case midas.ILADepthKey =>
      depth
    })

class ILADepth1024  extends WithILADepth(1024)
class ILADepth2048  extends WithILADepth(2048)
class ILADepth4096  extends WithILADepth(4096)
class ILADepth8192  extends WithILADepth(8192)
class ILADepth16384 extends WithILADepth(16384)

// ADDITIONAL TARGET TRANSFORMATIONS
// These run on the the Target FIRRTL before Target-toHost Bridges are extracted ,
// and decoupling is introduced

// Replaces Rocket Chip's black-box async resets with a synchronous equivalent
class WithAsyncResetReplacement
    extends Config((_, _, up) => { case TargetTransforms =>
      Dependency(firesim.passes.AsyncResetRegPass) +: up(TargetTransforms)
    })

// The wiring transform is normally only run as part of ReplSeqMem
class WithWiringTransform
    extends Config((_, _, up) => { case TargetTransforms =>
      Dependency[firrtl.passes.wiring.WiringTransform] +: up(TargetTransforms)
    })

// ADDITIONAL HOST TRANSFORMATIONS
// These run on the generated simulator(after all Golden Gate transformations:
// host-decoupling is introduced, and BridgeModules are elaborated)

// Tells ILATopWiringTransform to actually populate the ILA
class WithAutoILA
    extends Config((_, _, _) => { case midas.EnableAutoILA =>
      true
    })

// Implements the AutoCounter performace counters features
class WithAutoCounter
    extends Config((_, _, _) => { case midas.EnableAutoCounter =>
      true
    })

class WithAutoCounterPrintf
    extends Config((_, _, _) => {
      case midas.EnableAutoCounter        => true
      case midas.AutoCounterUsePrintfImpl => true
      case midas.SynthPrints              => true
    })

class BaseF1Config
    extends Config(
      new WithDefaultMemModel ++
        new WithWiringTransform ++
        new WithAsyncResetReplacement ++
        new midas.EC2F1Config
    )

class BaseXilinxAlveoU200Config
    extends Config(
      new WithDefaultMemModel ++
        new WithWiringTransform ++
        new WithAsyncResetReplacement ++
        new midas.XilinxAlveoU200Config
    )

class BaseXilinxAlveoU250Config
    extends Config(
      new WithDefaultMemModel ++
        new WithWiringTransform ++
        new WithAsyncResetReplacement ++
        new midas.XilinxAlveoU250Config
    )

class BaseXilinxAlveoU280Config
    extends Config(
      new WithDefaultMemModel ++
        new WithWiringTransform ++
        new WithAsyncResetReplacement ++
        new midas.XilinxAlveoU280Config
    )

class BaseNitefuryConfig
    extends Config(
      new WithDefaultMemModel ++
        new WithWiringTransform ++
        new WithAsyncResetReplacement ++
        new midas.NitefuryConfig
    )

class BaseXilinxVCU118Config
    extends Config(
      new WithDefaultMemModel ++
        new WithWiringTransform ++
        new WithAsyncResetReplacement ++
        new midas.XilinxVCU118Config
    )

class BaseVitisConfig
    extends Config(
      new WithDefaultMemModel ++
        new WithWiringTransform ++
        new WithAsyncResetReplacement ++
        new midas.VitisConfig
    )

class NoConfig extends Config(Parameters.empty)

class BaseBridgesConfig
    extends Config(
      new WithDefaultMemModel
    )

class DefaultF1Config
    extends Config(
      new BaseBridgesConfig ++
        new midas.F1Config
    )
class DefaultVitisConfig
    extends Config(
      new BaseBridgesConfig ++
        new midas.VitisConfig
    )
