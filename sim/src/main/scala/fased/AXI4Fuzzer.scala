//See LICENSE for license details.

package firesim.fased

import chisel3._
import chisel3.experimental.MultiIOModule

import freechips.rocketchip.diplomacy._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.config.Parameters


class AXI4Fuzzer(implicit p: Parameters) extends LazyModule {

  val nMemoryChannels = 1
  val master = LazyModule(new AXI4FuzzMaster(10000)) // txns
  val slave  = AXI4SlaveNode(Seq.tabulate(nMemoryChannels) { channel =>
    val base = Seq(AddressSet(BigInt(0), BigInt(0xFF)))

    AXI4SlavePortParameters(
      slaves = Seq(AXI4SlaveParameters(
        address       = base,
        regionType    = RegionType.UNCACHED,   // cacheable
        executable    = true,
        supportsWrite = TransferSizes(1, 128),
        supportsRead  = TransferSizes(1, 128),
        interleavedId = Some(0))),
      beatBytes = 16)
  })

  slave := master.node

  lazy val module = new LazyModuleImp(this) {
    val axi4 = IO(slave.in.head._1.cloneType)
    val done = IO(Output(Bool()))
    val error = IO(Output(Bool()))

    axi4 <> slave.in.head._1
    done := master.module.io.finished
    error := false.B
  }
}
