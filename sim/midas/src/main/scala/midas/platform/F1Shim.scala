package midas
package platform

import chisel3._
import chisel3.util._
import junctions._
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.diplomacy.{LazyModule}
import freechips.rocketchip.util.HeterogeneousBag

import midas.core.{DMANastiKey, HostMemNumChannels}
import midas.widgets.{AXI4Printf}

case object AXIDebugPrint extends Field[Boolean]

class F1ShimIO(implicit val p: Parameters) extends Bundle {
  val master = Flipped(new NastiIO()(p alterPartial ({ case NastiKey => p(MasterNastiKey) })))
  val dma    = Flipped(new NastiIO()(p alterPartial ({ case NastiKey => p(DMANastiKey) })))
}

class F1Shim(implicit p: Parameters) extends PlatformShim {
  val io = IO(new F1ShimIO)
  val top = Module(LazyModule(new midas.core.FPGATop).module)
  val io_slave = IO(HeterogeneousBag(top.mem.map(x => x.cloneType)))
  val headerConsts = top.headerConsts

  if (p(AXIDebugPrint)) {
    AXI4Printf(io.master, "master")
    AXI4Printf(io.dma,    "dma")
    io_slave.zipWithIndex foreach { case (io, idx) => AXI4Printf(io, s"slave_${idx}") }
  }

  top.io.ctrl <> io.master
  top.io.dma  <> io.dma
  io_slave.zip(top.mem).foreach({ case (io, bundle) => io <> bundle })

  val (wCounterValue, wCounterWrap) = Counter(io.master.aw.fire(), 4097)
  top.io.ctrl.aw.bits.id := wCounterValue

  val (rCounterValue, rCounterWrap) = Counter(io.master.ar.fire(), 4097)
  top.io.ctrl.ar.bits.id := rCounterValue

}
