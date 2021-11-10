package midas
package platform

import chisel3._
import chisel3.util._
import junctions._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy.{LazyModule, LazyRawModuleImp}
import freechips.rocketchip.util.HeterogeneousBag

import midas.core.{DMANastiKey}
import midas.widgets.{AXI4Printf, CtrlNastiKey}
import midas.stage.GoldenGateOutputFileAnnotation
import midas.platform.xilinx._

object VitisConstants {
  // Configurable through v++
  val kernelDefaultFreqMHz = 300.0
}

class VitisShim(implicit p: Parameters) extends PlatformShim {
  val ctrlAXI4BundleParams = AXI4BundleParameters(
    p(CtrlNastiKey).addrBits,
    p(CtrlNastiKey).dataBits,
    p(CtrlNastiKey).idBits)

  lazy val module = new LazyRawModuleImp(this) {
    val ap_rst_n    = IO(Input(AsyncReset()))
    val ap_clk      = IO(Input(Clock()))
    val s_axi_lite  = IO(Flipped(new XilinxAXI4Bundle(ctrlAXI4BundleParams, isAXI4Lite = true)))

    val ap_rst = (!ap_rst_n.asBool)

    // Setup Internal Clocking
    val firesimMMCM = Module(new MMCM(
      VitisConstants.kernelDefaultFreqMHz,
      p(DesiredHostFrequency),
      "firesim_clocking"))
    firesimMMCM.io.clk_in1 := ap_clk
    firesimMMCM.io.reset   := ap_rst.asAsyncReset

    val hostClock = firesimMMCM.io.clk_out1

    /**
      * Synchronizes an active high asynchronous reset.
      */
    def resetSync(areset: AsyncReset, clock: Clock, length: Int = 3): Bool = {
      withClockAndReset(clock, areset) {
        val sync_regs = Seq.fill(length)(RegInit(true.B))
        sync_regs.foldLeft(false.B) { case (prev, curr) => curr := prev; curr }
      }
    }

    // Synchronize asyncReset passed to kernel
    val hostAsyncReset = (ap_rst || !firesimMMCM.io.locked).asAsyncReset
    val hostSyncReset = resetSync(hostAsyncReset, hostClock)

    top.module.reset := hostSyncReset
    top.module.clock := hostClock

    // tie-off dma/io_slave interfaces
    top.module.dma.ar.valid := false.B
    top.module.dma.aw.valid := false.B
    top.module.dma.w.valid  := false.B
    top.module.dma.r.ready  := false.B
    top.module.dma.b.ready  := false.B

    top.module.mem.foreach({ case bundle =>
      bundle.ar.ready := false.B
      bundle.aw.ready := false.B
      bundle.w.ready  := false.B
      bundle.r.valid  := false.B
      bundle.b.valid  := false.B
    })

    val ctrl_cdc = Module(new AXI4ClockConverter(ctrlAXI4BundleParams, "ctrl_cdc", isAXI4Lite = true))
    ctrl_cdc.io.s_axi <> s_axi_lite
    ctrl_cdc.io.s_axi_aclk := ap_clk
    ctrl_cdc.io.s_axi_aresetn := (!ap_rst).asAsyncReset

    ctrl_cdc.io.m_axi_aclk := hostClock
    ctrl_cdc.io.m_axi_aresetn := (!hostSyncReset).asAsyncReset

    // All this awful block of code does is convert between three different
    // AXI4 bundle formats (Xilinx, RC Standard, Legacy Nasti).
    val axi4ToNasti = Module(new AXI42NastiIdentityModule(ctrlAXI4BundleParams))

    // Clock and reset are provided here only to enable assertion generation
    ctrl_cdc.io.m_axi.driveStandardAXI4(axi4ToNasti.io.axi4, hostClock, hostSyncReset)
    top.module.ctrl <> axi4ToNasti.io.nasti

    GoldenGateOutputFileAnnotation.annotateFromChisel(
      s"// Vitis Shim requires no dynamically generated macros \n",
      fileSuffix = ".defines.vh")
    GoldenGateOutputFileAnnotation.annotateFromChisel(
      s"# Currenty unused",
      ".env.tcl")
  }
}
