package midas.platform.xilinx

import chisel3._
import midas.stage.GoldenGateOutputFileAnnotation

object ResetSynchronizer {

  /** Black box wrapper for an XPM reset synchronizer See:
    * https://docs.xilinx.com/r/en-US/ug974-vivado-ultrascale-libraries/XPM_CDC_SYNC_RST
    */
  private class xpm_cdc_sync_rst(stages: Int, initValue: Boolean)
      extends BlackBox(
        Map(
          "DEST_SYNC_FF" -> stages,
          "INIT"         -> { if (initValue) 1 else 0 },
          "INIT_SYNC_FF" -> { if (initValue) 1 else 0 },
        )
      ) {
    val io = IO(new Bundle {
      val dest_clk = Input(Clock())
      val dest_rst = Output(Bool())
      val src_rst  = Input(Bool())
    })
  }

  /** Synchronizes a reset to a destination clock using a Xilinx Parameterized Macro (XPM) for which Vivado will
    * generate the correct timing and design constraints automatically.
    *
    * @param src_rst
    *   Input reset, could be AsyncReset, feel free to call toBool
    * @param dest_clk
    *   The clock driving the synchronization registers
    * @param initValue
    *   The value taken on by the sync registers at programming
    */
  def apply(
    src_rst:   Bool,
    dest_clk:  Clock,
    initValue: Boolean,
    stages:    Int = 4,
  ): Bool = {
    val xpm_sync = Module(new xpm_cdc_sync_rst(stages, initValue))
    xpm_sync.io.src_rst  := src_rst
    xpm_sync.io.dest_clk := dest_clk
    xpm_sync.io.dest_rst
  }
}
