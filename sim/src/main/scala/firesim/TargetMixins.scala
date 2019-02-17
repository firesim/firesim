package firesim.firesim

import chisel3._
import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.util._
import freechips.rocketchip.rocket.TracedInstruction
import firesim.endpoints.TraceOutputTop
import boom.system.BoomSubsystem

import midas.models.AXI4BundleWithEdge

/** Adds a port to the system intended to master an AXI4 DRAM controller. */
trait CanHaveMisalignedMasterAXI4MemPort { this: BaseSubsystem =>
  val module: CanHaveMisalignedMasterAXI4MemPortModuleImp

  private val memPortParamsOpt = p(ExtMem)
  private val portName = "axi4"
  private val device = new MemoryDevice
  val nMemoryChannels: Int

  val memAXI4Node = AXI4SlaveNode(Seq.tabulate(nMemoryChannels) { channel =>
    val params = memPortParamsOpt.get
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

  memPortParamsOpt.foreach { params =>
    memBuses.map { m =>
       memAXI4Node := m.toDRAMController(Some(portName)) {
        (AXI4UserYanker() := AXI4IdIndexer(params.idBits) := TLToAXI4())
      }
    }
  }
}

/** Actually generates the corresponding IO in the concrete Module */
trait CanHaveMisalignedMasterAXI4MemPortModuleImp extends LazyModuleImp {
  val outer: CanHaveMisalignedMasterAXI4MemPort

  val mem_axi4 = IO(new HeterogeneousBag(outer.memAXI4Node.in map AXI4BundleWithEdge.apply))
  (mem_axi4 zip outer.memAXI4Node.in).foreach { case (io, (bundle, _)) => io <> bundle }

  def connectSimAXIMem() {
    (mem_axi4 zip outer.memAXI4Node.in).foreach { case (io, (_, edge)) =>
      val mem = LazyModule(new SimAXIMem(edge, size = p(ExtMem).get.size))
      Module(mem.module).io.axi4.head <> io
    }
  }
}

trait CanHaveRocketTraceIO extends LazyModuleImp {
  val outer: RocketSubsystem

  val traced_params = outer.rocketTiles(0).p
  val tile_traces = outer.rocketTiles flatMap (tile => tile.module.trace.getOrElse(Nil))
  val traceIO = IO(Output(new TraceOutputTop(tile_traces.length)(traced_params)))
  traceIO.traces zip tile_traces foreach ({ case (ioconnect, trace) => ioconnect := trace })

  println(s"N tile traces: ${tile_traces.size}")
}

trait CanHaveBoomTraceIO extends LazyModuleImp {
  val outer: BoomSubsystem

  val traced_params = outer.boomTiles(0).p
  val tile_traces = outer.boomTiles flatMap (tile => tile.module.trace.getOrElse(Nil))
  val traceIO = IO(Output(new TraceOutputTop(tile_traces.length)(traced_params)))
  traceIO.traces zip tile_traces foreach ({ case (ioconnect, trace) => ioconnect := trace })

  println(s"N tile traces: ${tile_traces.size}")
}
