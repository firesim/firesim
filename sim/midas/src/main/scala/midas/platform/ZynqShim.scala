// See LICENSE for license details.

package midas.platform

import chisel3._
import junctions._
import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.diplomacy.{LazyModule, LazyModuleImp}

import midas.widgets.CtrlNastiKey

class ZynqShimIO(implicit p: Parameters) extends Bundle {
  val master = Flipped(new NastiIO()(p alterPartial ({ case NastiKey => p(CtrlNastiKey) })))
}

class ZynqShim(implicit p: Parameters) extends PlatformShim {
  lazy val module = new LazyModuleImp(this) {
    val io = IO(new ZynqShimIO)
    top.module.ctrl <> io.master
    val io_slave = IO(top.module.mem(0).cloneType)
    io_slave <> top.module.mem(0)
  }
}
