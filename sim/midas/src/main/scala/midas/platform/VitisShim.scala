package midas
package platform

import chisel3._
import junctions._
import freechips.rocketchip.amba.axi4._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy.LazyRawModuleImp

import midas.core.{HostMemChannelKey, FPGAManagedAXI4Key}
import midas.widgets.{CtrlNastiKey, AXI4Printf}
import midas.stage.GoldenGateOutputFileAnnotation
import midas.platform.xilinx._

object VitisConstants {
  // Configurable through v++
  val kernelDefaultFreqMHz = 300.0

  // This is wider than the addresses used in FPGATop
  val axi4MAddressBits = 64

  /** The hardcoded TCL variable name used to specify the simulator's frequency */
  val frequencyVariableName = "frequency"
}

class VitisShim(implicit p: Parameters) extends PlatformShim {
  val ctrlAXI4BundleParams =
    AXI4BundleParameters(p(CtrlNastiKey).addrBits, p(CtrlNastiKey).dataBits, p(CtrlNastiKey).idBits)

  val hostMemAXI4BundleParams = p(HostMemChannelKey).axi4BundleParams
    .copy(addrBits = VitisConstants.axi4MAddressBits)
  //val hostMemAXI4Offset = 0x4000000000L
  val hostMemAXI4Offset = 0x5000000000L

  val dmaAXI4BundleParams = p(FPGAManagedAXI4Key).get.axi4BundleParams
    .copy(addrBits = VitisConstants.axi4MAddressBits)
  val dmaAXI4Offset = 0x2000000000L

  lazy val module             = new LazyRawModuleImp(this) {
    val ap_rst_n   = IO(Input(AsyncReset()))
    val ap_clk     = IO(Input(Clock()))
    val s_axi_lite = IO(Flipped(new XilinxAXI4Bundle(ctrlAXI4BundleParams, isAXI4Lite = true)))
    val host_mem_0 = IO((new XilinxAXI4Bundle(hostMemAXI4BundleParams)))
    val m_dma        = IO((new XilinxAXI4Bundle(dmaAXI4BundleParams)))

    val ap_rst = (!ap_rst_n.asBool)

    // Setup Internal Clocking
    val firesimMMCM = Module(
      new MMCM(
        FrequencySpec.Static(VitisConstants.kernelDefaultFreqMHz),
        FrequencySpec.TCLVariable(VitisConstants.frequencyVariableName),
        "firesim_clocking",
      )
    )
    firesimMMCM.io.clk_in1 := ap_clk
    firesimMMCM.io.reset   := ap_rst.asAsyncReset

    val hostClock     = firesimMMCM.io.clk_out1
    val hostSyncReset = ResetSynchronizer(ap_rst || !firesimMMCM.io.locked, hostClock, initValue = true)
    top.module.reset := hostSyncReset
    top.module.clock := hostClock

    top.module.mem.foreach({ case bundle =>
      bundle.ar.ready := false.B
      bundle.aw.ready := false.B
      bundle.w.ready  := false.B
      bundle.r.valid  := false.B
      bundle.b.valid  := false.B
    })

    val ctrl_cdc = Module(new AXI4ClockConverter(ctrlAXI4BundleParams, "ctrl_cdc", isAXI4Lite = true))
    ctrl_cdc.io.s_axi         <> s_axi_lite
    ctrl_cdc.io.s_axi_aclk    := ap_clk
    ctrl_cdc.io.s_axi_aresetn := (!ap_rst).asAsyncReset

    ctrl_cdc.io.m_axi_aclk    := hostClock
    ctrl_cdc.io.m_axi_aresetn := (!hostSyncReset).asAsyncReset

    // All this awful block of code does is convert between three different
    // AXI4 bundle formats (Xilinx, RC Standard, Legacy Nasti).
    val axi4ToNasti = Module(new AXI42NastiIdentityModule(ctrlAXI4BundleParams))

    // Clock and reset are provided here only to enable assertion generation
    ctrl_cdc.io.m_axi.driveStandardAXI4(axi4ToNasti.io.axi4, hostClock, hostSyncReset)
    top.module.ctrl <> axi4ToNasti.io.nasti

    withClockAndReset(ap_clk, ap_rst) {
      AXI4Printf(host_mem_0, "host_mem_0")
    }

    if (top.module.mem.nonEmpty) {
      val addrWidth = VitisConstants.axi4MAddressBits.W

      val dram_0 = top.module.mem(0)

      withClockAndReset(hostClock, hostSyncReset) {
        AXI4Printf(dram_0, "top_mem_0")
      }

      val host_mem_cdc = Module(new AXI4ClockConverter(hostMemAXI4BundleParams, "host_mem_cdc"))
      host_mem_cdc.io.s_axi.drivenByStandardAXI4(dram_0, hostClock, hostSyncReset)
      host_mem_cdc.io.s_axi_aclk    := hostClock
      host_mem_cdc.io.s_axi_aresetn := (!hostSyncReset).asAsyncReset
      host_mem_cdc.io.s_axi.araddr  := hostMemAXI4Offset.U(addrWidth) + dram_0.ar.bits.addr
      host_mem_cdc.io.s_axi.awaddr  := hostMemAXI4Offset.U(addrWidth) + dram_0.aw.bits.addr
      host_mem_cdc.io.s_axi.arcache.foreach { _ := 3.U }
      host_mem_cdc.io.s_axi.awcache.foreach { _ := 3.U }

      host_mem_0                    <> host_mem_cdc.io.m_axi
      host_mem_cdc.io.m_axi_aclk    := ap_clk
      host_mem_cdc.io.m_axi_aresetn := ap_rst_n
    } else {
      host_mem_0.tieoffAsManager()
    }

    withClockAndReset(ap_clk, ap_rst) {
      AXI4Printf(m_dma, "m_dma")
    }

    top.module.fpga_managed_axi4.map { axi4 =>
      val addrWidth = VitisConstants.axi4MAddressBits.W

      withClockAndReset(hostClock, hostSyncReset) {
        AXI4Printf(axi4, "top_fpga_managed_axi4")
      }

      val dma_cdc = Module(new AXI4ClockConverter(dmaAXI4BundleParams, "dma_cdc"))
      dma_cdc.io.s_axi.drivenByStandardAXI4(axi4, hostClock, hostSyncReset)
      dma_cdc.io.s_axi_aclk    := hostClock
      dma_cdc.io.s_axi_aresetn := (!hostSyncReset).asAsyncReset
      dma_cdc.io.s_axi.araddr  := dmaAXI4Offset.U(addrWidth) + axi4.ar.bits.addr
      dma_cdc.io.s_axi.awaddr  := dmaAXI4Offset.U(addrWidth) + axi4.aw.bits.addr
      dma_cdc.io.s_axi.arcache.foreach { _ := 3.U }
      dma_cdc.io.s_axi.awcache.foreach { _ := 3.U }

      m_dma                    <> dma_cdc.io.m_axi
      dma_cdc.io.m_axi_aclk    := ap_clk
      dma_cdc.io.m_axi_aresetn := ap_rst_n
    }

    GoldenGateOutputFileAnnotation.annotateFromChisel(
      s"// Vitis Shim requires no dynamically generated macros \n",
      fileSuffix = ".defines.vh",
    )
    GoldenGateOutputFileAnnotation.annotateFromChisel(s"# Currently unused", ".env.tcl")
  }
}
