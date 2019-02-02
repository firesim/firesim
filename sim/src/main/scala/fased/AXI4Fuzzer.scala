//See LICENSE for license details.

package firesim.fased

import chisel3._
import chisel3.experimental.MultiIOModule

import freechips.rocketchip.diplomacy._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.config.Parameters

import midas.widgets.AXI4BundleWithEdge

// TODO: Handle errors and reinstatiate the TLErrorEvaluator
class AXI4Fuzzer(implicit p: Parameters) extends LazyModule with HasFuzzTarget {
  val nMemoryChannels = 1
  val fuzz  = LazyModule(new TLFuzzer(10000, overrideAddress = Some(fuzzAddr)))
  val model = LazyModule(new TLRAMModel("AXI4FuzzMaster"))
  val slave  = AXI4SlaveNode(Seq.tabulate(nMemoryChannels){ i => p(AXI4SlavePort) })

  (slave
    := AXI4UserYanker()
    := AXI4Deinterleaver(64)
    := TLToAXI4()
    := TLDelayer(0.1)
    := TLBuffer(BufferParams.flow)
    := TLDelayer(0.1)
    := model.node
    := fuzz.node)

  lazy val module = new LazyModuleImp(this) {
    val axi4 = IO(AXI4BundleWithEdge(slave.in.head))
    val done = IO(Output(Bool()))
    val error = IO(Output(Bool()))

    axi4 <> slave.in.head._1
    done := fuzz.module.io.finished
    error := false.B
  }
}
