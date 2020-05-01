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

case object AXIDebugPrint extends Field[Boolean]

class F1ShimIO(implicit val p: Parameters) extends Bundle {
  val master = Flipped(new NastiIO()(p alterPartial ({ case NastiKey => p(CtrlNastiKey) })))
  val dma    = Flipped(new NastiIO()(p alterPartial ({ case NastiKey => p(DMANastiKey) })))
}

class F1Shim(implicit p: Parameters) extends PlatformShim {
  lazy val module = new LazyModuleImp(this) {
    val io = IO(new F1ShimIO)
    val io_slave = IO(HeterogeneousBag(top.module.mem.map(x => x.cloneType)))

    if (p(AXIDebugPrint)) {
      AXI4Printf(io.master, "master")
      AXI4Printf(io.dma,    "dma")
      io_slave.zipWithIndex foreach { case (io, idx) => AXI4Printf(io, s"slave_${idx}") }
    }

    top.module.ctrl <> io.master
    top.module.dma  <> io.dma
    io_slave.zip(top.module.mem).foreach({ case (io, bundle) => io <> bundle })

    // Biancolin: It would be good to put in writing why ID is being reassigned...
    val (wCounterValue, wCounterWrap) = Counter(io.master.aw.fire(), 1 << p(CtrlNastiKey).idBits)
    top.module.ctrl.aw.bits.id := wCounterValue

    val (rCounterValue, rCounterWrap) = Counter(io.master.ar.fire(), 1 << p(CtrlNastiKey).idBits)
    top.module.ctrl.ar.bits.id := rCounterValue
  }
}
