// See LICENSE for license details.

package midas
package platform

import util.ParameterizedBundle // from rocketchip

import chisel3._
import chisel3.util._
import junctions._
import config.{Parameters, Field}

abstract class PlatformShim(implicit p: Parameters) extends Module {
  def top: midas.core.FPGATop
  def headerConsts: Seq[(String, Int)]
  def genHeader(sb: StringBuilder, target: String) {
    import widgets.CppGenerationUtils._
    sb.append(genStatic("TARGET_NAME", widgets.CStrLit(target)))
    sb.append(genMacro("PLATFORM_TYPE", s"V${this.getClass.getSimpleName}"))
    if (p(EnableSnapshot)) {
      sb append(genMacro("ENABLE_SNAPSHOT"))
      if (p(KeepSamplesInMem)) sb append(genMacro("KEEP_SAMPLES_IN_MEM"))
    }
    sb.append(genMacro("data_t", "uint%d_t".format(top.sim.channelWidth)))
    top.genHeader(sb)(top.sim.channelWidth)
    sb.append("\n// Simulation Constants\n")
    headerConsts map { case (name, value) =>
      genMacro(name, widgets.UInt32(value)) } addString sb
  }
}

case object MasterNastiKey extends Field[NastiParameters]
case object SlaveNastiKey extends Field[NastiParameters]

class ZynqShimIO(implicit p: Parameters) extends ParameterizedBundle()(p) {
  val master = Flipped(new NastiIO()(p alterPartial ({ case NastiKey => p(MasterNastiKey) })))
  val slave  = new NastiIO()(p alterPartial ({ case NastiKey => p(SlaveNastiKey) }))
}

class ZynqShim(simIo: midas.core.SimWrapperIO)
              (implicit p: Parameters) extends PlatformShim {
  val io = IO(new ZynqShimIO)
  val top = Module(new midas.core.FPGATop(simIo))
  val headerConsts = List(
    "MMIO_WIDTH" -> p(MasterNastiKey).dataBits / 8,
    "MEM_WIDTH"  -> p(SlaveNastiKey).dataBits / 8
  ) ++ top.headerConsts

  top.io.ctrl <> io.master
  io.slave <> top.io.mem
}
