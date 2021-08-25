package midas.platform

import chisel3._
import junctions._
import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.diplomacy.{LazyModule, LazyModuleImp, IdRange}
import freechips.rocketchip.util.HeterogeneousBag
import freechips.rocketchip.amba.axi4._

import midas.core.{DMANastiKey, HostMemChannelKey, HostMemNumChannels}
import midas.widgets.CtrlNastiKey
import midas.platform.xilinx._

class U250Shim(implicit p: Parameters) extends PlatformShim {
  // For now, create two seperate diplomatic graphs. One in the shim and one in FPGA top.
  // This so i don't need to fix meta-level simualtion + update other plaform shims.
  // TODO: Clean up by connecting the two diplomatic graphs: making the shims
  // define slave nodes (instead of doing it in FPGATop),
  val memoryController = LazyModule(new U250MIGIsland(p(HostMemChannelKey)))
  val fpgaTopMasterNode = AXI4MasterNode(Seq.fill(p(HostMemNumChannels)) {
    AXI4MasterPortParameters(
      masters = Seq(AXI4MasterParameters(
        name = "FPGATop",
        id   = IdRange(0, (1 << p(HostMemChannelKey).idBits) - 1))))
  })
  memoryController.crossAXI4In(memoryController.node) := fpgaTopMasterNode

  lazy val module = new LazyModuleImp(this) {
    val ctrl  = IO(Flipped(new NastiIO()(p alterPartial ({ case NastiKey => p(CtrlNastiKey) }))))
    val dma   = IO(Flipped(new NastiIO()(p alterPartial ({ case NastiKey => p(DMANastiKey) }))))
    val ddrInterfaces = IO(Vec(1, new Bundle with U250MIGIODDR))
    val migClockAndReset = IO(new Bundle with U250MIGIOClocksReset)

    top.module.ctrl <> ctrl
    top.module.dma  <> dma

    fpgaTopMasterNode.out.zip(top.module.mem).foreach({ case ((bundle, _), io) => bundle <> io })
    ddrInterfaces(0) <> memoryController.module.ddrIF
    migClockAndReset <> memoryController.module.clocksAndResets

    // TODO: Diplomatic clocking for mig islands
    memoryController.module.clock := migClockAndReset.c0_ddr4_ui_clk
    memoryController.module.reset := migClockAndReset.c0_ddr4_ui_clk_sync_rst
  }
}
