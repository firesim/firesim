// See LICENSE for license details.

package midas.targetutils.xdc

import chisel3.experimental.ChiselAnnotation

/** Some rough guidance, based on Ultrascale+, is provided in the scala doc for each hint. Consult the Xilinx UGs for
  * your target architecture and the synthesis UG (UG901).
  */

object QSFPPortLocHint {
  // _reg suffix is applied to memory cells by Vivado, the glob manages
  // duplication for multibit memories.
  private[midas] def portLoc: String = """
set_property -dict {PACKAGE_PIN AW19 IOSTANDARD LVDS} [get_ports default_300mhz_clk1_clk_n]
set_property -dict {PACKAGE_PIN AW20 IOSTANDARD LVDS} [get_ports default_300mhz_clk1_clk_p]

set_property -dict {PACKAGE_PIN E32  IOSTANDARD LVDS} [get_ports default_300mhz_clk2_clk_n];
set_property -dict {PACKAGE_PIN F32  IOSTANDARD LVDS} [get_ports default_300mhz_clk2_clk_p];
"""

  private def annotate(): Unit = {
    chisel3.experimental.annotate(new ChiselAnnotation {
      def toFirrtl = XDCAnnotation(XDCFiles.Implementation, portLoc)
    })
  }

  /** Annotates a chisel3 Mem indicating it should be implemented with a particular Xilinx RAM structure.
    */
  def apply(): Unit = {
    annotate()
  }
}
