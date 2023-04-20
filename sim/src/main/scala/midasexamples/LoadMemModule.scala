// See LICENSE for license details.

package firesim.midasexamples

import chisel3._

import freechips.rocketchip.diplomacy._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy.AddressSet

import junctions.NastiParameters
import midas.models.{AXI4EdgeSummary, BaseParams, CompleteConfig, FASEDBridge, LatencyPipeConfig}
import midas.widgets.{PeekPokeBridge, RationalClockBridge}

class ReaderIO extends Bundle {
  val addr    = Input(UInt(16.W))
  val data_lo = Output(UInt(32.W))
  val data_hi = Output(UInt(32.W))
  val done    = Output(Bool())
}

class MemoryReader(implicit p: Parameters) extends LazyModule {
  val node = TLClientNode(
    Seq(
      TLMasterPortParameters.v1(
        Seq(
          TLMasterParameters.v1(
            name     = "MemoryReader",
            sourceId = IdRange(0, 1),
          )
        )
      )
    )
  )

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new ReaderIO)

    val (out, edge) = node.out(0)

    val latchAddr  = RegInit(0xffff.U(16.W))
    val latchValue = RegInit(0.U(64.W))
    val pending    = RegInit(false.B)

    // Kick off a request with the address on the IO port.
    out.a.valid := !pending && latchAddr =/= io.addr

    val (legal, bits) = edge.Get(0.U, io.addr << 3.U, 3.U)
    out.a.bits := bits

    when(out.a.fire) {
      latchAddr := io.addr
      pending   := true.B
    }

    // Latch the result of the latest memory read.
    out.d.ready := pending
    when(out.d.fire) {
      latchValue := out.d.bits.data
      pending    := false.B
    }

    // Tie off unused channels.
    out.b.ready := true.B
    out.c.valid := false.B
    out.e.valid := false.B

    // Wire outputs to the harness.
    io.done    := !pending
    io.data_lo := latchValue(31, 0)
    io.data_hi := latchValue >> 32.U
  }
}

class LoadMemDUT(implicit p: Parameters) extends LazyModule {
  val addrBits        = 16
  val maxTransferSize = 64
  val beatBytes       = 8
  val idBits          = 1
  val nMemoryChannels = 1

  val xbar = AXI4Xbar()

  val slave = AXI4SlaveNode(Seq.tabulate(nMemoryChannels) { i =>
    val base   = AddressSet.misaligned(0, (BigInt(1) << addrBits))
    val filter = AddressSet(i * maxTransferSize, ~((nMemoryChannels - 1) * maxTransferSize))
    AXI4SlavePortParameters(
      slaves    = Seq(
        AXI4SlaveParameters(
          address       = base.flatMap(_.intersect(filter)),
          regionType    = RegionType.UNCACHED,
          executable    = true,
          supportsWrite = TransferSizes(1, maxTransferSize),
          supportsRead  = TransferSizes(1, maxTransferSize),
          interleavedId = Some(0),
        )
      ),
      beatBytes = beatBytes,
    )
  })

  (slave
    :*= AXI4Buffer()
    :*= AXI4UserYanker()
    :*= AXI4IdIndexer(idBits)
    :*= xbar)

  val reader = LazyModule(new MemoryReader)

  (xbar := AXI4Deinterleaver(maxTransferSize)
    := TLToAXI4()
    := TLDelayer(0.1)
    := TLBuffer(BufferParams.flow)
    := TLDelayer(0.1)
    := reader.node)

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new ReaderIO)
    io <> reader.module.io

    for ((axi4, edge) <- slave.in) {
      val nastiKey = NastiParameters(axi4.r.bits.data.getWidth, axi4.ar.bits.addr.getWidth, axi4.ar.bits.id.getWidth)
      FASEDBridge(
        clock,
        axi4,
        reset.asBool,
        CompleteConfig(
          new LatencyPipeConfig(
            BaseParams(maxReads = 16, maxWrites = 16, beatCounters = true, llcKey = None)
          ),
          nastiKey,
          Some(AXI4EdgeSummary(edge)),
          Some("DefaultMemoryRegion"),
        ),
      )
    }
  }
}

class LoadMemModule(implicit val p: Parameters) extends RawModule {
  val clock = RationalClockBridge().io.clocks.head
  val reset = WireInit(false.B)

  withClockAndReset(clock, reset) {
    val dut = Module((LazyModule(new LoadMemDUT)).module)
    PeekPokeBridge(
      clock,
      reset,
      ("addr", dut.io.addr),
      ("data_lo", dut.io.data_lo),
      ("data_hi", dut.io.data_hi),
      ("done", dut.io.done),
    )
  }
}
