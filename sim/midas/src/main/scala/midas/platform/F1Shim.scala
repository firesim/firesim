package midas
package platform

import chisel3._
import chisel3.util._

import org.chipsalliance.cde.config.{Field, Parameters}
import freechips.rocketchip.diplomacy.LazyModuleImp
import freechips.rocketchip.amba.axi4.AXI4Bundle

import junctions._
import midas.core.{CPUManagedAXI4Key, FPGAManagedAXI4Key, HostMemChannelKey, HostMemNumChannels}
import midas.widgets.{AXI4Printf, CtrlNastiKey}
import midas.stage.GoldenGateOutputFileAnnotation

import firesim.lib.nasti._

case object AXIDebugPrint extends Field[Boolean]

class F1Shim(implicit p: Parameters) extends PlatformShim {
  lazy val module = new LazyModuleImp(this) {

    if (p(F1ShimHasQSFPPorts)) {
      val qsfpCnt            = top.qsfpCnt
      val qsfpBitWidth       = p(FPGATopQSFPBitWidth)
      val io_qsfp_channel_up = IO(Vec(2, Input(Bool())))
      val io_qsfp_tx         = IO(Vec(2, Decoupled(UInt(qsfpBitWidth.W))))
      val io_qsfp_rx         = IO(Vec(2, Flipped(Decoupled(UInt(qsfpBitWidth.W)))))

      // tie down default values
      for (i <- 0 until 2) {
        io_qsfp_tx(i).valid := false.B
        io_qsfp_tx(i).bits  := 0.U
        io_qsfp_rx(i).ready := false.B
      }

      for (i <- 0 until qsfpCnt) {
        io_qsfp_tx(i)                 <> top.module.qsfp(i).tx
        top.module.qsfp(i).rx         <> io_qsfp_rx(i)
        top.module.qsfp(i).channel_up := io_qsfp_channel_up(i)
      }
    }

    val io_master = IO(Flipped(new NastiIO(p(CtrlNastiKey))))
    val io_pcis   = IO(Flipped(new NastiIO(CreateNastiParameters(p(CPUManagedAXI4Key).get.axi4BundleParams))))
    val io_slave  = IO(Vec(p(HostMemNumChannels), AXI4Bundle(p(HostMemChannelKey).axi4BundleParams)))

    if (p(AXIDebugPrint)) {
      AXI4Printf(io_master, "master")
      AXI4Printf(io_pcis, "pcis")
      io_slave.zipWithIndex.foreach { case (io, idx) => AXI4Printf(io, s"slave_${idx}") }
    }

    top.module.ctrl <> io_master

    // Connect the CPU-managed stream engine if the target has one. Otherwise, cap off the connection. (PCIS)
    top.module.cpu_managed_axi4 match {
      case None       =>
        io_pcis.aw.ready := false.B
        io_pcis.ar.ready := false.B
        io_pcis.w.ready  := false.B
        io_pcis.r.valid  := false.B
        io_pcis.r.bits   := DontCare
        io_pcis.b.valid  := false.B
        io_pcis.b.bits   := DontCare
      case Some(axi4) =>
        AXI4NastiAssigner.toAXI4Slave(axi4, io_pcis)
    }

    if (p(F1ShimHasPCIMPorts)) {
      val io_pcim = IO(new NastiIO(CreateNastiParameters(p(FPGAManagedAXI4Key).get.axi4BundleParams)))

      if (p(AXIDebugPrint)) {
        AXI4Printf(io_pcim, "pcim")
      }

      // Connect the FPGA-managed stream engine if the target has one. Otherwise, cap off the connection. (PCIM)
      top.module.fpga_managed_axi4 match {
        case None       =>
          io_pcim.aw.valid := false.B
          io_pcim.aw.bits  := DontCare // Leave unconnected
          io_pcim.ar.valid := false.B
          io_pcim.ar.bits  := DontCare
          io_pcim.w.valid  := false.B
          io_pcim.w.bits   := DontCare
          io_pcim.r.ready  := false.B
          io_pcim.b.ready  := false.B
        case Some(axi4) =>
          AXI4NastiAssigner.toAXI4Master(axi4, io_pcim)
      }
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
