package firesim

import Chisel._
import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.util._

/** Adds a port to the system intended to master an AXI4 DRAM controller. */
trait HasMisalignedMasterAXI4MemPort { this: BaseSubsystem =>
  val module: HasMisalignedMasterAXI4MemPortModuleImp

  private val params = p(ExtMem)
  private val portName = "axi4"
  private val device = new MemoryDevice
  val nMemoryChannels: Int

  val mem_axi4 = AXI4SlaveNode(Seq.tabulate(nMemoryChannels) { channel =>
    val base = AddressSet.misaligned(params.base, params.size)

    AXI4SlavePortParameters(
      slaves = Seq(AXI4SlaveParameters(
        address       = base,
        resources     = device.reg,
        regionType    = RegionType.UNCACHED,   // cacheable
        executable    = true,
        supportsWrite = TransferSizes(1, cacheBlockBytes),
        supportsRead  = TransferSizes(1, cacheBlockBytes),
        interleavedId = Some(0))),             // slave does not interleave read responses
      beatBytes = params.beatBytes)
  })

  memBuses.map { m =>
    mem_axi4 := m.toDRAMController(Some(portName)) {
      (AXI4UserYanker() := AXI4IdIndexer(params.idBits) := TLToAXI4())
    }
  }
}

/** Common io name and methods for propagating or tying off the port bundle */
trait HasMisalignedMasterAXI4MemPortBundle {
  implicit val p: Parameters
  val mem_axi4: HeterogeneousBag[AXI4Bundle]
  val nMemoryChannels: Int
  def connectSimAXIMem(dummy: Int = 1) = {
    if (nMemoryChannels > 0)  {
      val mem = LazyModule(new SimAXIMem(nMemoryChannels))
      Module(mem.module).io.axi4 <> mem_axi4
    }
  }
}

/** Actually generates the corresponding IO in the concrete Module */
trait HasMisalignedMasterAXI4MemPortModuleImp extends LazyModuleImp with HasMisalignedMasterAXI4MemPortBundle {
  val outer: HasMisalignedMasterAXI4MemPort

  val mem_axi4 = IO(HeterogeneousBag.fromNode(outer.mem_axi4.in))
  (mem_axi4 zip outer.mem_axi4.in) foreach { case (i, (o, _)) => i <> o }
  val nMemoryChannels = outer.nMemoryChannels
}
