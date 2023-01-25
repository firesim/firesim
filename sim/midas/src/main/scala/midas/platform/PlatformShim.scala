// See LICENSE for license details.

package midas.platform

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.diplomacy.{LazyModule}

import midas.Platform
import midas.core._
import midas.targetutils.xdc.SpecifyXDCCircuitPaths
import midas.{PreLinkCircuitPath, PostLinkCircuitPath}

/**
  * Generates the platform wrapper (which includes most of the chisel-generated
  * RTL that constitutes the simulator, including BridgeModules) using
  * parameters instance and the required annotations from the transformed
  * target design.
  *
  */
private [midas] object PlatformShim {
  def apply(config: SimWrapperConfig)(implicit p: Parameters): PlatformShim = {
    p(Platform)(p.alterPartial({ case SimWrapperKey => config }))
  }
}

abstract class PlatformShim(implicit p: Parameters) extends LazyModule()(p) {
  val top = LazyModule(new midas.core.FPGATop)

  def genHeader(sb: StringBuilder, target: String): Unit = {
    sb.append("#ifdef __cplusplus\n")

    sb.append("#include <cstddef>\n")
    sb.append("#include <cstdint>\n")
    sb.append("#include <cstdbool>\n")
    sb.append("#include <vector>\n")
    sb.append("#include <optional>\n")
    sb.append("#include \"core/config.h\"\n")

    top.module.genHeader(sb, target)

    sb.append("#endif // __cplusplus\n")
  }

  def genVHeader(sb: StringBuilder, target: String): Unit = {
    top.module.genVHeader(sb)
  }

  // Emit a `XDCPathToCircuitAnnotation` with the pre- and post-link circuit
  // paths provided in the configuration. These locate the design within the
  // context of the larger FPGA design.
  SpecifyXDCCircuitPaths(p(PreLinkCircuitPath), p(PostLinkCircuitPath))
}
