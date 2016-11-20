package midas
package platform

import util.ParameterizedBundle // from rocketchip
import chisel3._
import chisel3.util._
import junctions._
import cde.{Parameters, Field}

abstract class PlatformShim extends Module {
  def top: midas.core.FPGATop
}

case object MasterNastiKey extends Field[NastiParameters]
case object SlaveNastiKey extends Field[NastiParameters]

class ZynqShimIO(implicit p: Parameters) extends ParameterizedBundle()(p) {
  val master = Flipped(new NastiIO()(p alter Map(NastiKey -> p(MasterNastiKey))))
  val slave  = new NastiIO()(p alter Map(NastiKey -> p(SlaveNastiKey)))
}

class ZynqShim(simIo: midas.core.SimWrapperIO,
               memIo: midas.core.SimMemIO)
              (implicit p: Parameters) extends PlatformShim {
  val io = IO(new ZynqShimIO)
  val top = Module(new midas.core.FPGATop(simIo, memIo))

  top.io.ctrl <> io.master
  io.slave <> top.io.mem
}
