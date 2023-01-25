// See LICENSE for license details.

package midas.widgets

import chisel3._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.amba.axi4._

class AXI4AddressTranslation(offset: BigInt, bridgeAddressSets: Seq[AddressSet], regionName: String)(implicit p: Parameters) extends LazyModule {
  val node = AXI4AdapterNode(
    // TODO: It's only safe to do this if all slaves are homogenous. Assert that.
    slaveFn  = { sp => sp.copy(slaves = Seq(sp.slaves.head.copy(address = bridgeAddressSets)))},
    masterFn = { p => p })

  val virtualBase  = bridgeAddressSets.map(_.base).min
  val virtualBound = bridgeAddressSets.map(_.max).max
  lazy val module = new LazyModuleImp(this) {
    (node.in zip node.out) foreach { case ((in, edgeIn), (out, edgeOut)) =>
      val maxHostAddr = BigInt(1) << out.ar.bits.params.addrBits
      out <> in
      // Adjust the target address to the correct host memory location. offset
      // may be negative (when the target address space itself is
      // offset; typically to 2 GiB in rocket-chip targets), so adding
      // maxHostAddr produces a non-negative UInt literal.  Additionally, offset
      // may be larger than the total available memory space (ex. if the host
      // has less than < 2 GiB of host DRAM), hence %.
      out.aw.bits.addr := in.aw.bits.addr + (maxHostAddr + (offset % maxHostAddr)).U
      out.ar.bits.addr := in.ar.bits.addr + (maxHostAddr + (offset % maxHostAddr)).U
      assert(~in.aw.valid || in.aw.bits.addr <= virtualBound.U, s"AW request address in memory region ${regionName} exceeds region bound.")
      assert(~in.ar.valid || in.ar.bits.addr <= virtualBound.U, s"AR request address in memory region ${regionName} exceeds region bound.")
      assert(~in.aw.valid || in.aw.bits.addr >= virtualBase.U,  s"AW request address in memory region ${regionName} is less than region base.")
      assert(~in.ar.valid || in.ar.bits.addr >= virtualBase.U,  s"AR request address in memory region ${regionName} is less than region base.")
    }
  }
}

object AXI4AddressTranslation {
  def apply(offset: BigInt, bridgeAddressSets: Seq[AddressSet], regionName: String)(implicit p: Parameters): AXI4Node = {
    val axi4AddrOffset = LazyModule(new AXI4AddressTranslation(offset, bridgeAddressSets, regionName))
    axi4AddrOffset.node
  }
}
