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

  val memAXI4Node = p(ExtMem).map { case MemoryPortParams(memPortParams, nMemoryChannels) =>
    val portName = "axi4"
    val device = new MemoryDevice

    val memAXI4Node = AXI4SlaveNode(Seq.tabulate(nMemoryChannels) { channel =>
      val base = AddressSet.misaligned(memPortParams.base, memPortParams.size)
      val filter = AddressSet(channel * mbus.blockBytes, ~((nMemoryChannels-1) * mbus.blockBytes))

      AXI4SlavePortParameters(
        slaves = Seq(AXI4SlaveParameters(
          address       = base.flatMap(_.intersect(filter)),
          resources     = device.reg,
          regionType    = RegionType.UNCACHED, // cacheable
          executable    = true,
          supportsWrite = TransferSizes(1, mbus.blockBytes),
          supportsRead  = TransferSizes(1, mbus.blockBytes),
          interleavedId = Some(0))), // slave does not interleave read responses
        beatBytes = memPortParams.beatBytes)
    })

    memAXI4Node := mbus.toDRAMController(Some(portName)) {
      AXI4UserYanker() := AXI4IdIndexer(memPortParams.idBits) := TLToAXI4()
    }

    memAXI4Node
  }
}

/** Actually generates the corresponding IO in the concrete Module */
trait CanHaveMisalignedMasterAXI4MemPortModuleImp extends LazyModuleImp {
  val outer: CanHaveMisalignedMasterAXI4MemPort

  val mem_axi4 = outer.memAXI4Node.map(x => IO(HeterogeneousBag(AXI4BundleWithEdge.fromNode(x.in))))
  (mem_axi4 zip outer.memAXI4Node) foreach { case (io, node) =>
    (io zip node.in).foreach { case (io, (bundle, _)) => io <> bundle }
  }

  def connectSimAXIMem() {
    (mem_axi4 zip outer.memAXI4Node).foreach { case (io, node) =>
      (io zip node.in).foreach { case (io, (_, edge)) =>
        val mem = LazyModule(new SimAXIMem(edge, size = p(ExtMem).get.master.size))
        Module(mem.module).io.axi4.head <> io
      }
    }
  }
}

///* Deploy once we bump to RC's unaligned support */
//trait CanHaveFASEDCompatibleAXI4MemPortModuleImp extends CanHaveMasterAXI4MemPortModuleImp {
//  val outer: CanHaveMasterAXI4MemPort
//
//  // :nohacks: JANK :nohacks:- --------------V
//  override val mem_axi4 = outer.memAXI4Node.map(x => Wire(HeterogeneousBag.fromNode(x.in)))
//
//  val mem_axi4_with_edge = outer.memAXI4Node.map(n => IO(HeterogeneousBag(AXI4BundleWithEdge.fromNode(n.in)))) 
//  mem_axi4_with_edge.get <> mem_axi4.get
//
//}

trait CanHaveRocketTraceIO extends LazyModuleImp {
  val outer: HasTiles

  val tracedParams = outer.tiles(0).p
  val tileTraceNodes = outer.tiles.map(tile => tile.traceNode)
  val traceIO = IO(Output(new TraceOutputTop(tileTraceNodes.length)(tracedParams)))
  traceIO.traces zip tileTraceNodes foreach ({ case (ioconnect, trace) => ioconnect := trace.in.head._1 })

  println(s"N tile traces: ${tileTraceNodes.size}")
}

trait CanHaveBoomTraceIO extends CanHaveRocketTraceIO
