package midas.platform.xilinx

import chisel3._
import midas.stage.GoldenGateOutputFileAnnotation

/** Boxing classes that can be used to emit a string that encodes a frequency for MMCM generation
  */
sealed trait FrequencySpec {
  def value: String
}

object FrequencySpec {

  /** Provides a hardcoded frequency value in the IP generation script */
  case class Static(freqMHz: Double) extends FrequencySpec { def value = f"{${freqMHz}%5.2f}" }

  /** Looks up a value provided by the TCL script sourcing this snippet */
  case class TCLVariable(name: String) extends FrequencySpec { def value = s"$$${name}" }
}

class MMCM(
  inputFreqMHz:             FrequencySpec,
  outputFreqMHz:            FrequencySpec,
  override val desiredName: String,
) extends BlackBox {
  val io = IO(new Bundle {
    val clk_in1  = Input(Clock())
    val clk_out1 = Output(Clock())
    val reset    = Input(AsyncReset()) // Active high
    val locked   = Output(Bool())
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
        |                         CONFIG.PRIM_IN_FREQ ${inputFreqMHz.value} \\
        |                         CONFIG.CLKOUT1_REQUESTED_OUT_FREQ ${outputFreqMHz.value} \\
        |                         CONFIG.CLKOUT1_DRIVES {Buffer}] \\
        |             [get_ips ${desiredName}]
        |""".stripMargin,
    s".${desiredName}.ipgen.tcl",
  )
}
