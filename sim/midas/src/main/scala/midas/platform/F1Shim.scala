package midas
package platform

import chisel3._
import chisel3.util._
import junctions._
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy.{LazyModule, LazyModuleImp}
import freechips.rocketchip.util.HeterogeneousBag

import midas.core.{CPUManagedAXI4Key}
import midas.widgets.{AXI4Printf, CtrlNastiKey}
import midas.stage.GoldenGateOutputFileAnnotation
import midas.targetutils.xdc._

case object AXIDebugPrint extends Field[Boolean]

class F1Shim(implicit p: Parameters) extends PlatformShim {
  lazy val module = new LazyModuleImp(this) {
    val io_master = IO(Flipped(new NastiIO()(p alterPartial { case NastiKey => p(CtrlNastiKey) })))
    val io_dma    = IO(Flipped(new NastiIO()(p alterPartial {
      case NastiKey => NastiParameters(p(CPUManagedAXI4Key).get.axi4BundleParams) })))
    val io_slave = IO(HeterogeneousBag(top.module.mem.map(x => x.cloneType)))

    if (p(AXIDebugPrint)) {
      AXI4Printf(io_master, "master")
      AXI4Printf(io_dma,    "dma")
      io_slave.zipWithIndex foreach { case (io, idx) => AXI4Printf(io, s"slave_${idx}") }
    }

    top.module.ctrl <> io_master
    AXI4NastiAssigner.toAXI4(top.module.cpu_managed_axi4.get, io_dma)
    io_slave.zip(top.module.mem).foreach({ case (io, bundle) => io <> bundle })

    // Biancolin: It would be good to put in writing why ID is being reassigned...
    val (wCounterValue, wCounterWrap) = Counter(io_master.aw.fire, 1 << p(CtrlNastiKey).idBits)
    top.module.ctrl.aw.bits.id := wCounterValue

    val (rCounterValue, rCounterWrap) = Counter(io_master.ar.fire, 1 << p(CtrlNastiKey).idBits)
    top.module.ctrl.ar.bits.id := rCounterValue

    // Capture FPGA-toolflow related verilog defines
    def channelInUse(idx: Int): String = if (idx < top.dramChannelsRequired) "1" else "0"

    GoldenGateOutputFileAnnotation.annotateFromChisel(
      s"""|// Optionally instantiate additional memory channels if required.
          |// The first channel (C) is provided by the shell and is not optional.
          |`define USE_DDR_CHANNEL_A ${channelInUse(1)}
          |`define USE_DDR_CHANNEL_B ${channelInUse(2)}
          |`define USE_DDR_CHANNEL_D ${channelInUse(3)}
          |""".stripMargin,
      fileSuffix = ".defines.vh")
  }
}
