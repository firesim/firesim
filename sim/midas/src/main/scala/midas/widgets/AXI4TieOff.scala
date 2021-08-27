// See LICENSE for license details.

package midas.widgets

import chisel3._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.amba.axi4._

/**
  * Ties off an AXI4 edge by spoofing a master that drives no requests.
  */
class AXI4TieOff(implicit p: Parameters) extends LazyModule {
  val node = AXI4MasterNode(Seq(AXI4MasterPortParameters(Seq(AXI4MasterParameters(name = "TiedOff")))))
  lazy val module = new LazyModuleImp(this) {
    for ((axi4out, _) <- node.out) { axi4out.tieoff }
  }
}

object AXI4TieOff {
  def apply()(implicit p: Parameters): AXI4OutwardNode = {
    val tieoff = LazyModule(new AXI4TieOff)
    tieoff.node
  }
}
