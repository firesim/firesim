// See LICENSE for license details.

package midas.platform

import chisel3._
import freechips.rocketchip.config.{Parameters}

import midas.{EnableSnapshot, KeepSamplesInMem}
import midas.core.ChannelWidth
import midas.widgets.{CStrLit, UInt32}
import midas.widgets.CppGenerationUtils._

abstract class PlatformShim(implicit p: Parameters) extends MultiIOModule {
  def top: midas.core.FPGATopImp
  def headerConsts: Seq[(String, Long)]
  def genHeader(sb: StringBuilder, target: String) {
    sb.append("#include <stdint.h>\n")
    sb.append(genStatic("TARGET_NAME", CStrLit(target)))
    sb.append(genMacro("PLATFORM_TYPE", s"V${this.getClass.getSimpleName}"))
    if (p(EnableSnapshot)) {
      sb append(genMacro("ENABLE_SNAPSHOT"))
      if (p(KeepSamplesInMem)) sb append(genMacro("KEEP_SAMPLES_IN_MEM"))
    }
    sb.append(genMacro("data_t", "uint%d_t".format(p(ChannelWidth))))
    top.genHeader(sb)
    sb.append("\n// Simulation Constants\n")
    headerConsts map { case (name, value) =>
      genMacro(name, UInt32(value)) } addString sb
  }
}
