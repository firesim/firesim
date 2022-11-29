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
import freechips.rocketchip.diplomacy.{TransferSizes}

import java.io.{File}

// Provides a function to elaborate the top-level platform shim
case object Platform extends Field[(Parameters) => PlatformShim]
// Switches to synthesize prints and assertions
case object SynthAsserts extends Field[Boolean]
case object SynthPrints extends Field[Boolean]

/** When set, [[targetutils.FPGADebugAnnotation]]s (signals labelled with FPGADebug())
  * are automatically wired out to an ILA.
  */
case object EnableAutoILA extends Field[Boolean](false)

/** Sets a per-probe buffer depth on the ILA. A greater value permits capturing a longer
  * waveform, at the expense of FPGA reasources. See PG172
  * ([200~https://docs.xilinx.com/v/u/en-US/pg172-ila) for more info.
  */
case object ILADepthKey extends Field[Int](1024)

/** Sets the number of comparators to be generated per ILA-probe.
  * See PG172 ([200~https://docs.xilinx.com/v/u/en-US/pg172-ila) for more
  * info.
  */
case object ILAProbeTriggersKey extends Field[Int](2)

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

// Provides the absolute paths to firrtl-emitted module in the context of the
// FPGA project before and after linking. If the firrtl-emitted module is the
// top-level, set the path to None.
case object PreLinkCircuitPath extends Field[Option[String]](None)
case object PostLinkCircuitPath extends Field[Option[String]](None)

// Alias WithoutTLMonitors into this package so that it can be used in config strings
class WithoutTLMonitors extends freechips.rocketchip.subsystem.WithoutTLMonitors

class SimConfig extends Config (new Config((site, here, up) => {
  case SynthAsserts     => false
  case SynthPrints      => false
  case AXIDebugPrint    => false
  // TODO remove
  case HasDMAChannel    => site(CPUManagedAXI4Key).nonEmpty
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
  case StreamEngineInstantiatorKey => (e: StreamEngineParameters, p: Parameters) => new CPUManagedStreamEngine(p, e)
  case CPUManagedAXI4Key => Some(CPUManagedAXI4Params(
    addrBits = 64,
    dataBits = 512,
    idBits = 6,
  ))
  case FPGAManagedAXI4Key   => None
  case CtrlNastiKey   => NastiParameters(32, 25, 12)
  case HostMemChannelKey => HostMemChannelParams(
    size      = 0x400000000L, // 16 GiB
    beatBytes = 8,
    idBits    = 16)
  case HostMemNumChannels => 4
  case PreLinkCircuitPath => Some("firesim_top")
  case PostLinkCircuitPath => Some("WRAPPER_INST/CL/firesim_top")
}) ++ new SimConfig)

class VitisConfig extends Config(new Config((site, here, up) => {
  case Platform       => (p: Parameters) => new VitisShim()(p)
  case CPUManagedAXI4Key => None
  case FPGAManagedAXI4Key   =>
    val dataBits = 512
    Some(FPGAManagedAXI4Params(
    // This value was chosen arbitrarily. Vitis makes it natural to
    // request multiples of 1 GiB, and we may wish to expand this as after some
    // performance analysis.
    size = 4096 * 1024,
    dataBits = dataBits,
    // This was chosen to match the AXI4 recommendations and could change.
    idBits = 4,
    // Don't support narrow reads/writes, and cap at a page per the AXI5 spec
    writeTransferSizes = TransferSizes(dataBits / 8, 4096),
    readTransferSizes  = TransferSizes(dataBits / 8, 4096)
  ))
  case StreamEngineInstantiatorKey => (e: StreamEngineParameters, p: Parameters) => new FPGAManagedStreamEngine(p, e)
  // Notes on width selection for the control bus
  // Address: This needs further investigation. 12 may not be sufficient when using many auto counters
  // ID:      AXI4Lite does not use ID bits. Use one here since Nasti (which
  //          lacks a native AXI4LITE implementation) can't handle 0-width wires.
  case CtrlNastiKey   => NastiParameters(32, 12, 1)
  case HostMemChannelKey => HostMemChannelParams(
    size      = 0x400000000L, // 16 GiB
    beatBytes = 8,
    idBits    = 16)
  // This could be as many as four on a U250, but support for the other
  // channels requires adding address offsets in the shim (TODO).
  case HostMemNumChannels => 1
  // We don't need to provide circuit paths because
  // 1) The Shim module is the top-level of the kernel
  // 2) Implementation constraints are scoped to the kernel level in our vitis flow
  case PreLinkCircuitPath => None
  case PostLinkCircuitPath => None
}) ++ new SimConfig)

// Turns on all additional synthesizable debug features for checking the
// implementation of the simulator.
class HostDebugFeatures extends Config((site, here, up) => {
  case GenerateTokenIrrevocabilityAssertions => true
})

