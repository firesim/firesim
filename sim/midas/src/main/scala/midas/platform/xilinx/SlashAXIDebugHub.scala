// See LICENSE for license details.

package midas.platform.xilinx

import chisel3._
import freechips.rocketchip.amba.axi4.AXI4BundleParameters
import midas.stage.GoldenGateOutputFileAnnotation

/** Optional Versal debug transport with explicit AXIS links inside the RM. */
class SlashAXIDebugHub extends BlackBox {
  override def desiredName = "slash_axi_debug_hub"
  val io                   = IO(new Bundle {
    val aclk            = Input(Clock())
    val aresetn         = Input(Bool())
    val s_axi           = Flipped(new XilinxAXI4Bundle(AXI4BundleParameters(21, 32, 1)))
    val s_axi_awregion  = Input(UInt(4.W))
    val s_axi_arregion  = Input(UInt(4.W))
    val s00_axis_tdata  = Input(UInt(32.W))
    val s00_axis_tvalid = Input(Bool())
    val s00_axis_tlast  = Input(Bool())
    val s00_axis_tready = Output(Bool())
    val m00_axis_tdata  = Output(UInt(32.W))
    val m00_axis_tvalid = Output(Bool())
    val m00_axis_tlast  = Output(Bool())
    val m00_axis_tready = Input(Bool())
  })
  GoldenGateOutputFileAnnotation.annotateFromChisel(
    """create_ip -name axi_dbg_hub -vendor xilinx.com -library ip -version 2.0 -module_name slash_axi_debug_hub
      |set_property -dict [list CONFIG.C_NUM_DEBUG_CORES {1} CONFIG.C_AXI_DATA_WIDTH {32} CONFIG.C_AXI_ADDR_WIDTH {21} CONFIG.C_AXI_ID_WIDTH {1} CONFIG.C_ADDR_OFFSET {0x00000200000}] [get_ips slash_axi_debug_hub]
      |""".stripMargin,
    ".slash_axi_debug_hub.ipgen.tcl",
  )
}
