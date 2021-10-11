package midas
package platform

import chisel3._
import chisel3.util._
import junctions._
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy.{LazyModule, LazyModuleImp}
import freechips.rocketchip.util.HeterogeneousBag

import midas.core.{DMANastiKey}
import midas.widgets.{AXI4Printf, CtrlNastiKey}
import midas.stage.GoldenGateOutputFileAnnotation

class VitisShim(implicit p: Parameters) extends PlatformShim {
  lazy val module = new LazyModuleImp(this) {
    val io = IO(new Bundle{
      val master = Flipped(new NastiIO()(p alterPartial ({ case NastiKey => p(CtrlNastiKey) })))
      val dma  = Flipped(new NastiIO()(p alterPartial ({ case NastiKey => p(DMANastiKey) })))
    })
    val io_slave  = IO(HeterogeneousBag(top.module.mem.map(x => x.cloneType)))

    top.module.ctrl <> io.master
    //top.module.dma  <> io.dma
    //io_slave.zip(top.module.mem).foreach({ case (io, bundle) => io <> bundle })

    // tie-off dma/io_slave interfaces
    top.module.dma.ar.valid := false.B
    top.module.dma.aw.valid := false.B
    top.module.dma.w.valid  := false.B
    top.module.dma.r.ready  := false.B
    top.module.dma.b.ready  := false.B

    io.dma.ar.ready := false.B
    io.dma.aw.ready := false.B
    io.dma.w.ready  := false.B
    io.dma.r.valid  := false.B
    io.dma.b.valid  := false.B

    top.module.mem.foreach({ case bundle =>
      bundle.ar.ready := false.B
      bundle.aw.ready := false.B
      bundle.w.ready  := false.B
      bundle.r.valid  := false.B
      bundle.b.valid  := false.B
    })

    io_slave.foreach({ case bundle =>
      bundle.ar.valid := false.B
      bundle.aw.valid := false.B
      bundle.w.valid  := false.B
      bundle.r.ready  := false.B
      bundle.b.ready  := false.B
    })

    // Biancolin: It would be good to put in writing why ID is being reassigned...
    val (wCounterValue, wCounterWrap) = Counter(io.master.aw.fire(), 1 << p(CtrlNastiKey).idBits)
    top.module.ctrl.aw.bits.id := wCounterValue

    val (rCounterValue, rCounterWrap) = Counter(io.master.ar.fire(), 1 << p(CtrlNastiKey).idBits)
    top.module.ctrl.ar.bits.id := rCounterValue

    // Capture FPGA-toolflow related verilog defines
    def channelInUse(idx: Int): String = if (idx < top.dramChannelsRequired) "1" else "0"

    GoldenGateOutputFileAnnotation.annotateFromChisel(
      s"""|// Optionally instantiate additional memory channels if required.
          |// The first channel (C) is provided by the shell and is not optional.
          |`define USE_DDR_CHANNEL_A ${channelInUse(1)}
          |`define USE_DDR_CHANNEL_B ${channelInUse(2)}
          |`define USE_DDR_CHANNEL_D ${channelInUse(3)}
          |""".stripMargin,
      fileSuffix = ".defines.vh")
  }
}
