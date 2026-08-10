//See LICENSE for license details.
package firesim.fasedtests

import chisel3._
import chisel3.util._

import freechips.rocketchip.diplomacy._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.{Field, Parameters}
import freechips.rocketchip.subsystem.{ExtMem, MemoryPortParams}

import midas.models.AXI4EdgeSummary

import firesim.lib.bridges.{
  CompleteConfig,
  FASEDBridge,
  PeekPokeBridge,
  RationalClockBridge,
  ResetPulseBridge,
  ResetPulseBridgeParameters,
}
import firesim.lib.nasti.{NastiIO, NastiParameters}

/** A TL master that issues sequential Put (write) requests, mimicking DMA behavior.
  * Writes 8 bytes at a time to consecutive addresses.
  */
class TLSequentialWriter(
  nOperations: Int,
  inFlight: Int = 16,
  baseAddr: BigInt = 0,
  addrRange: BigInt = 1 << 16,
)(implicit p: Parameters) extends LazyModule {
  val node = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(
    TLMasterParameters.v1(name = "SeqWriter", sourceId = IdRange(0, inFlight))
  ))))

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val finished = Output(Bool())
    })

    val (out, edge) = node.out(0)
    val beatBytes = edge.manager.beatBytes
    val addrBits = log2Ceil(addrRange)

    val num_reqs = RegInit(nOperations.U(log2Up(nOperations + 1).W))
    val num_resps = RegInit(nOperations.U(log2Up(nOperations + 1).W))
    io.finished := num_resps === 0.U

    // Sequential address counter, wrapping within range
    val addr_counter = RegInit(0.U(addrBits.W))
    val addr_step = beatBytes.U

    // Source ID tracking
    val idMap = Module(new IDMapGenerator(inFlight))

    // A channel: sequential writes
    val writeData = RegInit(0.U((beatBytes * 8).W))
    val (legal, putBits) = edge.Put(
      fromSource = idMap.io.alloc.bits,
      toAddress = baseAddr.U | addr_counter,
      lgSize = log2Ceil(beatBytes).U,
      data = writeData
    )

    val a_valid = num_reqs =/= 0.U && idMap.io.alloc.valid
    out.a.valid := a_valid
    out.a.bits := putBits
    idMap.io.alloc.ready := out.a.fire

    when(out.a.fire) {
      num_reqs := num_reqs - 1.U
      addr_counter := Mux(addr_counter + addr_step >= addrRange.U, 0.U, addr_counter + addr_step)
      writeData := writeData + 1.U
    }

    // D channel: accept responses, free source IDs
    out.d.ready := true.B
    idMap.io.free.valid := out.d.fire
    idMap.io.free.bits := out.d.bits.source

    when(out.d.fire) {
      num_resps := num_resps - 1.U
    }
  }
}

/** A TL master that issues random Get (read) requests, mimicking scattered core cache misses. */
class TLRandomReader(
  nOperations: Int,
  inFlight: Int = 8,
  baseAddr: BigInt = 0,
  addrRange: BigInt = 1 << 16,
)(implicit p: Parameters) extends LazyModule {
  val node = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(
    TLMasterParameters.v1(name = "RandReader", sourceId = IdRange(0, inFlight))
  ))))

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new Bundle {
      val finished = Output(Bool())
      val hung = Output(Bool())
    })

    val (out, edge) = node.out(0)
    val beatBytes = edge.manager.beatBytes
    val addrBits = log2Ceil(addrRange)

    val num_reqs = RegInit(nOperations.U(log2Up(nOperations + 1).W))
    val num_resps = RegInit(nOperations.U(log2Up(nOperations + 1).W))
    io.finished := num_resps === 0.U

    // Random address generation — use upper bits of LFSR to maximize DRAM row conflicts.
    // DDR3 rows are ~16KB, so we need addresses that differ in bits [addrBits-1:14]
    // to hit different rows. Shift the LFSR to scatter across the full address range.
    val lfsr = LFSR64()
    // Use LFSR bits directly for the full address, ensuring row bits are randomized
    val randAddr = (lfsr(addrBits - 1, log2Ceil(beatBytes))) ## 0.U(log2Ceil(beatBytes).W)

    // Source ID tracking
    val idMap = Module(new IDMapGenerator(inFlight))

    // A channel: random reads
    val (legal, getBits) = edge.Get(
      fromSource = idMap.io.alloc.bits,
      toAddress = baseAddr.U | randAddr,
      lgSize = log2Ceil(beatBytes).U
    )

    val a_valid = num_reqs =/= 0.U && idMap.io.alloc.valid
    out.a.valid := a_valid
    out.a.bits := getBits
    idMap.io.alloc.ready := out.a.fire

    when(out.a.fire) {
      num_reqs := num_reqs - 1.U
    }

    // D channel: accept responses, free source IDs
    out.d.ready := true.B
    idMap.io.free.valid := out.d.fire
    idMap.io.free.bits := out.d.bits.source

    when(out.d.fire) {
      num_resps := num_resps - 1.U
    }

    // Hang detection: if we're waiting for a response and don't get one for 8192 cycles
    val idle_counter = RegInit(0.U(14.W))
    val outstanding = num_reqs =/= num_resps || (num_reqs === 0.U && num_resps =/= 0.U)
    when(out.d.fire || !outstanding) {
      idle_counter := 0.U
    }.otherwise {
      idle_counter := idle_counter + 1.U
    }
    io.hung := idle_counter >= 8192.U
    assert(!io.hung, "TLRandomReader hung: no response for 8192 cycles")
  }
}

case object SeqWriterOps extends Field[Int](100000)
case object SeqWriterFlight extends Field[Int](16)
case object RandReaderOps extends Field[Int](100000)
case object RandReaderFlight extends Field[Int](2)

/** DUT with one sequential writer (DMA-like) and one random reader (core-like),
  * both targeting the same memory through the FASED model.
  */
class DMAStarvationDUT(implicit p: Parameters) extends LazyModule {
  val addrRange = BigInt(1) << p(AddrBits)

  // Writer: sequential writes within a small region (~16KB = 1 DRAM row) for maximum row hits
  val writerRange = BigInt(1) << 14  // 16KB, fits in one DRAM row
  val writer = LazyModule(new TLSequentialWriter(
    nOperations = p(SeqWriterOps),
    inFlight = p(SeqWriterFlight),
    baseAddr = 0,
    addrRange = writerRange,
  ))
  // Reader: random reads across the full address space to maximize row misses
  val reader = LazyModule(new TLRandomReader(
    nOperations = p(RandReaderOps),
    inFlight = p(RandReaderFlight),
    baseAddr = 0,
    addrRange = addrRange,
  ))

  val MemoryPortParams(portParams, nMemoryChannels, _) = p(ExtMem).get
  val slave = AXI4SlaveNode(Seq.tabulate(nMemoryChannels) { i =>
    val base = AddressSet.misaligned(0, addrRange)
    val filter = AddressSet(i * p(MaxTransferSize), ~((nMemoryChannels - 1) * p(MaxTransferSize)))
    AXI4SlavePortParameters(
      slaves = Seq(AXI4SlaveParameters(
        address = base.flatMap(_.intersect(filter)),
        regionType = RegionType.UNCACHED,
        executable = true,
        supportsWrite = TransferSizes(1, p(MaxTransferSize)),
        supportsRead = TransferSizes(1, p(MaxTransferSize)),
        interleavedId = Some(0),
      )),
      beatBytes = p(BeatBytes),
    )
  })

  // Merge at TL level first, then convert to AXI4 once (avoids AXI4TLStateField width mismatch)
  val tlXbar = TLXbar()
  tlXbar := TLBuffer(BufferParams.flow) := writer.node
  tlXbar := TLBuffer(BufferParams.flow) := reader.node

  val xbar = AXI4Xbar()
  (slave
    :*= AXI4Buffer()
    :*= AXI4UserYanker()
    :*= AXI4IdIndexer(p(IDBits))
    :*= xbar)

  (xbar
    := AXI4Deinterleaver(p(MaxTransferSize))
    := TLToAXI4()
    := tlXbar)

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val done = IO(Output(Bool()))
    val error = IO(Output(Bool()))

    done := !reset.asBool && writer.module.io.finished && reader.module.io.finished
    error := reader.module.io.hung
    for ((axi4, edge) <- slave.in) {
      val nastiKey = NastiParameters(axi4.r.bits.data.getWidth, axi4.ar.bits.addr.getWidth, axi4.ar.bits.id.getWidth)
      val nastiIo = Wire(new NastiIO(nastiKey))
      junctions.AXI4NastiAssigner.toNasti(nastiIo, axi4)
      FASEDBridge(
        clock,
        nastiIo,
        reset.asBool,
        CompleteConfig(nastiKey, Some(AXI4EdgeSummary.createCompatEdgeSummary(edge)), Some("DefaultMemoryRegion")),
      )
    }
  }
}

class DMAStarvationFuzzer(implicit val p: Parameters) extends RawModule {
  val reset = WireInit(false.B)
  val clockBridge = RationalClockBridge()
  val clock = clockBridge.io.clocks(0)

  val resetBridge = Module(new ResetPulseBridge(ResetPulseBridgeParameters()))
  resetBridge.io.clock := clock
  withClockAndReset(clock, resetBridge.io.reset) {
    val dummyReset = WireInit(false.B)
    val dut = Module((LazyModule(new DMAStarvationDUT)).module)
    PeekPokeBridge(clock, dummyReset, ("done", dut.done), ("error", dut.error))
  }
}
