// See LICENSE for license details.

package midas.widgets

import chisel3._
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.amba.axi4._

import midas.targetutils.FpgaDebug

case object EnableAXI4ILATaps extends Field[Boolean](false)
class AXI4ILAMonitor(args: AXI4MonitorArgs) extends AXI4MonitorBase(args) {
  def legalize(bundle: AXI4Bundle, edge: AXI4EdgeParameters, reset: Reset): Unit = {
    println("Generating AXI4 ILA")
    FpgaDebug(bundle)
  }
}
