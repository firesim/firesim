//See LICENSE for license details.

package firesim.fased

import chisel3._
import chisel3.experimental.MultiIOModule

import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.amba.axi4.{AXI4Bundle, AXI4BundleParameters}


class AXI4Fuzzer extends MultiIOModule {
  val axi4 = IO(AXI4Bundle(AXI4BundleParameters(addrBits = 34, dataBits = 128, idBits = 4)))
  val done = IO(Output(Bool()))
  val error = IO(Output(Bool()))
  axi4 <> DontCare
  done := true.B
  error := false.B
}
