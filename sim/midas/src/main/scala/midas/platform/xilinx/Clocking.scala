package midas.platform.xilinx

import chisel3._
import midas.stage.GoldenGateOutputFileAnnotation

class MMCM(inputFreqMHz: Double, outputFreqMHz: Double, override val desiredName: String) extends BlackBox {
  val io = IO(new Bundle {
    val clk_in1   = Input(Clock())
    val clk_out1  = Output(Clock())
    val reset     = Input(AsyncReset()) // Active high
    val locked    = Output(Bool())
  })

  GoldenGateOutputFileAnnotation.annotateFromChisel(
    s"""|
        |create_ip -name clk_wiz \\
        |          -vendor xilinx.com \\
        |          -library ip \\
        |          -version 6.0 \\
        |          -module_name ${desiredName}
        |
        |set_property -dict [list CONFIG.USE_PHASE_ALIGNMENT {false} \\
        |                         CONFIG.PRIM_SOURCE {No_buffer} \\
        |                         CONFIG.PRIM_IN_FREQ {${inputFreqMHz}} \\
        |                         CONFIG.CLKOUT1_REQUESTED_OUT_FREQ {${outputFreqMHz}} \\
        |                         CONFIG.CLKOUT1_DRIVES {Buffer}] \\
        |             [get_ips ${desiredName}]
        |""".stripMargin,
    s".${desiredName}.ipgen.tcl"
  )
}
