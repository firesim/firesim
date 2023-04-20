package midas
package platform

import chisel3._
import chisel3.util._
import junctions._
import org.chipsalliance.cde.config.{Field, Parameters}
import freechips.rocketchip.diplomacy.LazyModuleImp
import freechips.rocketchip.amba.axi4.AXI4Bundle

import midas.core.{CPUManagedAXI4Key, HostMemChannelKey, HostMemNumChannels}
import midas.widgets.{AXI4Printf, CtrlNastiKey}
import midas.stage.GoldenGateOutputFileAnnotation

case object AXIDebugPrint extends Field[Boolean]

class F1Shim(implicit p: Parameters) extends PlatformShim {
  lazy val module = new LazyModuleImp(this) {
    val io_master = IO(Flipped(new NastiIO()(p.alterPartial { case NastiKey => p(CtrlNastiKey) })))
    val io_dma    = IO(Flipped(new NastiIO()(p.alterPartial { case NastiKey =>
      NastiParameters(p(CPUManagedAXI4Key).get.axi4BundleParams)
    })))
    val io_slave  = IO(Vec(p(HostMemNumChannels), AXI4Bundle(p(HostMemChannelKey).axi4BundleParams)))

    if (p(AXIDebugPrint)) {
      AXI4Printf(io_master, "master")
      AXI4Printf(io_dma, "dma")
      io_slave.zipWithIndex.foreach { case (io, idx) => AXI4Printf(io, s"slave_${idx}") }
    }

    top.module.ctrl <> io_master

    // Connect the CPU-managed stream engine if the target has one. Otherwise, cap off the connection.
    top.module.cpu_managed_axi4 match {
      case None       =>
        io_dma.aw.ready := false.B
        io_dma.ar.ready := false.B
        io_dma.w.ready  := false.B
        io_dma.r.valid  := false.B
        io_dma.r.bits   := DontCare
        io_dma.b.valid  := false.B
        io_dma.b.bits   := DontCare
      case Some(axi4) =>
        AXI4NastiAssigner.toAXI4(axi4, io_dma)
    }

    // Using last-connect semantics, first cap off all channels and then connect the ones used.
    for (slave <- io_slave) {
      slave.aw.valid := false.B
      slave.aw.bits  := DontCare
      slave.ar.valid := false.B
      slave.ar.bits  := DontCare
      slave.w.valid  := false.B
      slave.w.bits   := DontCare
      slave.r.ready  := false.B
      slave.b.ready  := false.B
    }
    io_slave.zip(top.module.mem).foreach({ case (io, bundle) => io <> bundle })

    // Biancolin: It would be good to put in writing why ID is being reassigned...
    val (wCounterValue, wCounterWrap) = Counter(io_master.aw.fire, 1 << p(CtrlNastiKey).idBits)
    top.module.ctrl.aw.bits.id := wCounterValue

    val (rCounterValue, rCounterWrap) = Counter(io_master.ar.fire, 1 << p(CtrlNastiKey).idBits)
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
      fileSuffix = ".defines.vh",
    )
  }
}
