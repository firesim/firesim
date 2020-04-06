// See LICENSE for license details.

package midas.platform

import chisel3._
import junctions._
import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.diplomacy.{LazyModule}

import midas.widgets.CtrlNastiKey

class ZynqShimIO(implicit p: Parameters) extends Bundle {
  val master = Flipped(new NastiIO()(p alterPartial ({ case NastiKey => p(CtrlNastiKey) })))
}

class ZynqShim(implicit p: Parameters) extends PlatformShim {
  val io = IO(new ZynqShimIO)
  val top = Module(LazyModule(new midas.core.FPGATop).module)
  val headerConsts = top.headerConsts

  top.io.ctrl <> io.master

  val io_slave = IO(top.mem(0).cloneType)
  io_slave <> top.mem(0)
}
