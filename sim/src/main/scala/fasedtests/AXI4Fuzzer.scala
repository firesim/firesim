//See LICENSE for license details.

package firesim.fasedtests

import chisel3._

import freechips.rocketchip.diplomacy._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.subsystem.{ExtMem, MemoryPortParams}

import junctions.NastiParameters
import midas.models.{FASEDBridge, AXI4EdgeSummary, CompleteConfig}
import midas.widgets.{PeekPokeBridge, RationalClockBridge, ResetPulseBridge, ResetPulseBridgeParameters}

case class FuzzerParameters(numTransactions: Int, maxFlight: Int, overrideAddress: Option[AddressSet])
case object FuzzerParametersKey extends Field[Seq[FuzzerParameters]]()

class AXI4FuzzerDUT(implicit p: Parameters) extends LazyModule with HasFuzzTarget {
  val fuzzerModelPairs  = for ((params, idx) <- p(FuzzerParametersKey).zipWithIndex) yield {
    val fuzzer =  LazyModule(new TLFuzzer(
      params.numTransactions,
      params.maxFlight,
      overrideAddress = params.overrideAddress))

    val model = LazyModule(new TLRAMModel(s"AXI4FuzzMaster_${idx}"))
    (fuzzer, model)
  }

  val (fuzzers, models) = fuzzerModelPairs.unzip
  val xbar = AXI4Xbar()
  val MemoryPortParams(portParams, nMemoryChannels, _) = p(ExtMem).get
  val slave  = AXI4SlaveNode(Seq.tabulate(nMemoryChannels){ i =>
    val base = AddressSet.misaligned(0, (BigInt(1) << p(AddrBits)))
    val filter = AddressSet(i * p(MaxTransferSize), ~((nMemoryChannels-1) * p(MaxTransferSize)))
    AXI4SlavePortParameters(
      slaves = Seq(AXI4SlaveParameters(
        address       = base.flatMap(_.intersect(filter)),
        regionType    = RegionType.UNCACHED,
        executable    = true,
        supportsWrite = TransferSizes(1, p(MaxTransferSize)),
        supportsRead  = TransferSizes(1, p(MaxTransferSize)),
        interleavedId = Some(0))),
      beatBytes = p(BeatBytes))
  })

  (slave
    :*= AXI4Buffer() // Required to cut combinational paths through FASED instance
    :*= AXI4UserYanker()
    :*= AXI4IdIndexer(p(IDBits))
    :*= xbar)

  for ((fuzz, model) <- fuzzerModelPairs) {
    (xbar := AXI4Deinterleaver(p(MaxTransferSize))
      := TLToAXI4()
      := TLDelayer(0.1)
      := TLBuffer(BufferParams.flow)
      // Should there be a Filter in here?
      := TLDelayer(0.1)
      := model.node
      := fuzz.node)
  }

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val done = IO(Output(Bool()))
    val error = IO(Output(Bool()))

    done := fuzzers.map(_.module.io.finished).reduce(_ && _)
    error := false.B
    for ((axi4, edge) <- slave.in) {
      val nastiKey = NastiParameters(axi4.r.bits.data.getWidth,
                                     axi4.ar.bits.addr.getWidth,
                                     axi4.ar.bits.id.getWidth)
      val fasedInstance =  FASEDBridge(clock, axi4, reset.asBool,
        CompleteConfig(p(firesim.configs.MemModelKey),
                       nastiKey,
                       Some(AXI4EdgeSummary(edge)),
                       Some("DefaultMemoryRegion")))
    }
  }
}

class AXI4Fuzzer(implicit val p: Parameters) extends RawModule {
  val reset = WireInit(false.B)
  val clockBridge = RationalClockBridge()
  val clock = clockBridge.io.clocks(0)

  val resetBridge = Module(new ResetPulseBridge(ResetPulseBridgeParameters()))
  resetBridge.io.clock := clock
  withClockAndReset(clock, resetBridge.io.reset) {
    val dummyReset = WireInit(false.B)
    val fuzzer = Module((LazyModule(new AXI4FuzzerDUT)).module)
    val peekPokeBridge = PeekPokeBridge(clock, dummyReset,
                                            ("done", fuzzer.done),
                                            ("error", fuzzer.error))
  }
}
