// See LICENSE for license details.

package midas
package platform

import chisel3._
import chisel3.util._
import junctions._
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy.{LazyModule}
import freechips.rocketchip.util.ParameterizedBundle

import midas.core.ChannelWidth

abstract class PlatformShim(implicit p: Parameters) extends MultiIOModule {
  def top: midas.core.FPGATopImp
  def headerConsts: Seq[(String, Long)]
  def genHeader(sb: StringBuilder, target: String) {
    import widgets.CppGenerationUtils._
    sb.append("#include <stdint.h>\n")
    sb.append(genStatic("TARGET_NAME", widgets.CStrLit(target)))
    sb.append(genMacro("PLATFORM_TYPE", s"V${this.getClass.getSimpleName}"))
    if (p(EnableSnapshot)) {
      sb append(genMacro("ENABLE_SNAPSHOT"))
      if (p(KeepSamplesInMem)) sb append(genMacro("KEEP_SAMPLES_IN_MEM"))
    }
    sb.append(genMacro("data_t", "uint%d_t".format(p(ChannelWidth))))
    top.genHeader(sb)
    sb.append("\n// Simulation Constants\n")
    headerConsts map { case (name, value) =>
      genMacro(name, widgets.UInt32(value)) } addString sb
  }
}

case object MasterNastiKey extends Field[NastiParameters]

class ZynqShimIO(implicit p: Parameters) extends ParameterizedBundle()(p) {
  val master = Flipped(new NastiIO()(p alterPartial ({ case NastiKey => p(MasterNastiKey) })))
}

class ZynqShim(implicit p: Parameters) extends PlatformShim {
  val io = IO(new ZynqShimIO)
  val top = Module(LazyModule(new midas.core.FPGATop).module)
  val headerConsts = top.headerConsts

  top.io.ctrl <> io.master

  val io_slave = IO(top.mem(0).cloneType)
  io_slave <> top.mem(0)
}
