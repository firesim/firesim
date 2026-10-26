// See LICENSE for license details.

package midas.platform

import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy.LazyModule

import midas.Platform
import midas.core._
import midas.targetutils.xdc.SpecifyXDCCircuitPaths
import midas.{PostLinkCircuitPath, PreLinkCircuitPath}

/** Generates the platform wrapper (which includes most of the chisel-generated RTL that constitutes the simulator,
  * including BridgeModules) using parameters instance and the required annotations from the transformed target design.
  */
private[midas] object PlatformShim {
  def apply(config: SimWrapperConfig)(implicit p: Parameters): PlatformShim = {
    // Field defaults are singleton objects. A mutable allocator must belong to
    // this elaboration, rather than retain stream indices from an earlier one.
    val toCPU    = new StreamAllocator
    val fromCPU  = new StreamAllocator
    val toQSFP   = new StreamAllocator
    val fromQSFP = new StreamAllocator
    val toPeer   = new StreamAllocator
    p(Platform)(p.alterPartial {
      case SimWrapperKey                => config
      case ToCPUStreamAllocatorKey      => toCPU
      case FromCPUStreamAllocatorKey    => fromCPU
      case ToQSFPStreamAllocatorKey     => toQSFP
      case FromQSFPStreamAllocatorKey   => fromQSFP
      case ToPeerFPGAStreamAllocatorKey => toPeer
    })
  }
}

abstract class PlatformShim(implicit p: Parameters) extends LazyModule()(p) {
  val top = LazyModule(new midas.core.FPGATop)

  def genHeader(sb: StringBuilder, target: String): Unit = {
    top.module.genHeader(sb, target)
  }

  def genVHeader(sb: StringBuilder): Unit = {
    top.module.genVHeader(sb)
  }

  def genPartitioningHeader(sb: StringBuilder): Unit = {
    top.module.genPartitioningConstants(sb)
  }

  def genPeerToPeerAddrMap(sb: StringBuilder): Unit = {
    top.module.genPeerToPeerAddrMap(sb)
  }

  // Emit a `XDCPathToCircuitAnnotation` with the pre- and post-link circuit
  // paths provided in the configuration. These locate the design within the
  // context of the larger FPGA design.
  SpecifyXDCCircuitPaths(p(PreLinkCircuitPath), p(PostLinkCircuitPath))
}
