//See LICENSE for license details.

package firesim.fasedtests

import chisel3._

import freechips.rocketchip.diplomacy._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.config.Parameters

import junctions.{NastiKey, NastiParameters}
import midas.models.{FASEDBridge, AXI4EdgeSummary, CompleteConfig}
import midas.widgets.{PeekPokeBridge}

object AXI4Printf {
  def apply(axi4: AXI4Bundle): Unit = {
    val tCycle = RegInit(0.U(32.W))
    tCycle.suggestName("tCycle")
    tCycle := tCycle + 1.U

    when (axi4.ar.fire) {
      printf("TCYCLE: %d,  AR addr: %x, id: %d, size: %d, len: %d\n",
        tCycle,
        axi4.ar.bits.addr,
        axi4.ar.bits.id,
        axi4.ar.bits.size,
        axi4.ar.bits.len)
    }

    when (axi4.aw.fire) {
      printf("TCYCLE: %d,  AW addr: %x, id: %d, size: %d, len: %d\n",
        tCycle,
        axi4.aw.bits.addr,
        axi4.aw.bits.id,
        axi4.aw.bits.size,
        axi4.aw.bits.len)
    }
    when (axi4.w.fire) {
      printf("TCYCLE: %d,  W data: %x, last: %b\n",
        tCycle,
        axi4.w.bits.data,
        axi4.w.bits.last)
    }

    when (axi4.r.fire) {
      printf("TCYCLE: %d,  R data: %x, last: %b, id: %d\n",
        tCycle,
        axi4.r.bits.data,
        axi4.r.bits.last,
        axi4.r.bits.id)
    }
    when (axi4.b.fire) {
      printf("TCYCLE: %d,  B id: %d\n", tCycle, axi4.r.bits.id)
    }
  }
}



// TODO: Handle errors and reinstatiate the TLErrorEvaluator
class AXI4FuzzerDUT(implicit p: Parameters) extends LazyModule with HasFuzzTarget {
  val nMemoryChannels = 1
  val fuzz  = LazyModule(new TLFuzzer(p(NumTransactions), p(MaxFlight)))
  val model = LazyModule(new TLRAMModel("AXI4FuzzMaster"))
  val slave  = AXI4SlaveNode(Seq.tabulate(nMemoryChannels){ i => p(AXI4SlavePort) })

  (slave
    := AXI4Buffer() // Required to cut combinational paths through FASED instance
    := AXI4UserYanker()
    := AXI4IdIndexer(p(IDBits))
    := TLToAXI4()
    := TLDelayer(0.1)
    := TLBuffer(BufferParams.flow)
    := TLDelayer(0.1)
    := model.node
    := fuzz.node)

  lazy val module = new LazyModuleImp(this) {
    val axi4 = IO(slave.in.head._1.cloneType)
    val axi4Edge = slave.in.head._2
    val done = IO(Output(Bool()))
    val error = IO(Output(Bool()))

    axi4 <> slave.in.head._1
    done := fuzz.module.io.finished
    error := false.B
    AXI4Printf(axi4)
  }
}

class AXI4Fuzzer(implicit val p: Parameters) extends RawModule {
  val clock = IO(Input(Clock()))
  val reset = WireInit(false.B)

  withClockAndReset(clock, reset) {
    val fuzzer = Module((LazyModule(new AXI4FuzzerDUT)).module)
    val nastiKey = NastiParameters(fuzzer.axi4.r.bits.data.getWidth,
                                   fuzzer.axi4.ar.bits.addr.getWidth,
                                   fuzzer.axi4.ar.bits.id.getWidth)

    val fasedInstance =  FASEDBridge(fuzzer.axi4, reset,
      CompleteConfig(p(firesim.configs.MemModelKey), nastiKey, Some(AXI4EdgeSummary(fuzzer.axi4Edge))))
    val peekPokeBridge = PeekPokeBridge(reset,
                                            ("done", fuzzer.done),
                                            ("error", fuzzer.error))
  }
}
