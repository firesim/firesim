// See LICENSE for license details.

package midas

import core._
import widgets._
import platform._
import models._
import firrtl.stage.TransformManager.TransformDependency
import junctions.{NastiKey, NastiParameters}
import freechips.rocketchip.config.{Parameters, Config, Field}
import freechips.rocketchip.unittest.UnitTests

import java.io.{File}

// Provides a function to elaborate the top-level platform shim
case object Platform extends Field[(Parameters) => PlatformShim]
// Switches to synthesize prints and assertions
case object SynthAsserts extends Field[Boolean]
case object SynthPrints extends Field[Boolean]

// When False FpgaDebug() annotations are ignored
case object EnableAutoILA extends Field[Boolean](false)

// Auto Counter Switches
case object EnableAutoCounter extends Field[Boolean](false)
/**
  * Chooses between the two implementation strategies for Auto Counter.
  *
  * True: Synthesized Printf Implementation
  *       - Generates a counter directly in the target module, adds a printf,
  *         and annotates it for printf synthesis
  *       - Pros: cycle-exact event resolution; counters are printed every time the event is asserted
  *       - Cons: considerably more resource intensive (64-bit values are synthesized in the printf)
  *       Biancolin: This seems like a waste of bandwidth? Maybe just print the message?
  *
  * False: Native Bridge Implementation (Default)
  *        - Wires out each annotated event (Bool) to a dedicated AutoCounter bridge.
  *        - Pros: More resource efficient;
  *        - Cons: Coarse event resolution (depends on the sampling frequency set in the bridge)
  */
case object AutoCounterUsePrintfImpl extends Field[Boolean](false)

case object HasDMAChannel extends Field[Boolean]

// Enables multi-cycle RAM model generation (as demonstrated in the ICCAD2019 paper)
case object GenerateMultiCycleRamModels extends Field[Boolean](false)

// Enables multithreading of repeated instances of annotated models
case object EnableModelMultiThreading extends Field[Boolean](false)

// User provided transforms to run before Golden Gate transformations
// These are constructor functions accept a Parameters instance and produce a
// sequence of firrtl Transforms to run
case object TargetTransforms extends Field[Seq[TransformDependency]](Seq())
// User provided transforms to run after Golden Gate transformations
case object HostTransforms extends Field[Seq[TransformDependency]](Seq())

// Directory into which output files are dumped. Set by -td when invoking the Stage
case object OutputDir extends Field[File]

// Alias WithoutTLMonitors into this package so that it can be used in config strings
class WithoutTLMonitors extends freechips.rocketchip.subsystem.WithoutTLMonitors

class SimConfig extends Config (new Config((site, here, up) => {
  case SynthAsserts     => false
  case SynthPrints      => false
  case DMANastiKey      => NastiParameters(512, 64, 6)
  case AXIDebugPrint    => false

  // Remove once AXI4 port is complete
  case MemNastiKey      => {
    NastiParameters(
      addrBits = chisel3.util.log2Ceil(site(HostMemChannelKey).size * site(HostMemNumChannels)),
      dataBits = site(HostMemChannelKey).beatBytes * 8,
      idBits   = 6)
  }
}) ++ new WithoutTLMonitors)

class F1Config extends Config(new Config((site, here, up) => {
  case Platform       => (p: Parameters) => new F1Shim()(p)
  case HasDMAChannel  => true
  case CtrlNastiKey   => NastiParameters(32, 25, 12)
  case HostMemChannelKey => HostMemChannelParams(
    size      = 0x400000000L, // 16 GiB
    beatBytes = 8,
    idBits    = 16)
  case HostMemNumChannels => 4
}) ++ new SimConfig)

// Turns on all additional synthesizable debug features for checking the
// implementation of the simulator.
class HostDebugFeatures extends Config((site, here, up) => {
  case GenerateTokenIrrevocabilityAssertions => true
})

object DesiredHostFrequency extends Field[Int](90) // Host FPGA frequency, in MHz

class WithDesiredHostFrequency(freq: Int) extends Config((site, here, up) => {
    case DesiredHostFrequency => freq
})

// Shortened names useful for appending to config strings in Make variables and build recipes
class F190MHz extends WithDesiredHostFrequency(190)
class F175MHz extends WithDesiredHostFrequency(175)
class F160MHz extends WithDesiredHostFrequency(160)
class F150MHz extends WithDesiredHostFrequency(150)
class F140MHz extends WithDesiredHostFrequency(140)
class F130MHz extends WithDesiredHostFrequency(130)
class F120MHz extends WithDesiredHostFrequency(120)
class F110MHz extends WithDesiredHostFrequency(110)
class F100MHz extends WithDesiredHostFrequency(100)
class  F90MHz extends WithDesiredHostFrequency(90)
class  F85MHz extends WithDesiredHostFrequency(85)
class  F80MHz extends WithDesiredHostFrequency(80)
class  F75MHz extends WithDesiredHostFrequency(75)
class  F70MHz extends WithDesiredHostFrequency(70)
class  F65MHz extends WithDesiredHostFrequency(65)
class  F60MHz extends WithDesiredHostFrequency(60)
class  F55MHz extends WithDesiredHostFrequency(55)
class  F50MHz extends WithDesiredHostFrequency(50)
class  F45MHz extends WithDesiredHostFrequency(45)
class  F40MHz extends WithDesiredHostFrequency(40)
class  F35MHz extends WithDesiredHostFrequency(35)
class  F30MHz extends WithDesiredHostFrequency(30)
