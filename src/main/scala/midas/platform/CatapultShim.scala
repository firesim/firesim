package midas
package platform

import util.ParameterizedBundle // from rocketchip
import widgets._
import chisel3._
import chisel3.util._
import cde.{Parameters, Field}
import junctions._

case object PCIeWidth extends Field[Int]

class PCIeBundle(implicit p: Parameters) extends ParameterizedBundle()(p) {
  val ctrlKey = p(widgets.CtrlNastiKey)
  val data = UInt(width=ctrlKey.dataBits)
  val addr = UInt(width=ctrlKey.addrBits)
  val isWr = UInt(width=8)
}

class CatapultShimIO(implicit p: Parameters) extends ParameterizedBundle()(p) {
  val pcie = new Bundle {
    val in = Flipped(Decoupled(UInt(width=p(PCIeWidth))))
    val out = Decoupled(UInt(width=p(PCIeWidth)))
  }
  // TODO: UMI
}

class CatapultShim(simIo: midas.core.SimWrapperIO,
                   memIo: midas.core.SimMemIO)
                  (implicit p: Parameters) extends PlatformShim {
  val ctrlKey = p(widgets.CtrlNastiKey)
  val io = IO(new CatapultShimIO)
  val top = Module(new midas.core.FPGATop(simIo, memIo))
  val vtype = "VCatapultShim"
  val headerConsts = List(
    "PCIE_WIDTH"      -> p(PCIeWidth),
    "MMIO_WIDTH"      -> p(PCIeWidth) / 8,
    "MMIO_ADDR_WIDTH" -> ctrlKey.addrBits / 8,
    "MMIO_DATA_WIDTH" -> ctrlKey.dataBits / 8,
    "MEM_WIDTH"       -> 0 // Todo
  ) ++ top.headerConsts

  // PCIe Input  
  val pcieIn = (new PCIeBundle).fromBits(io.pcie.in.bits)
  val sIdle :: sWrite :: sWrAck :: Nil = Enum(UInt(), 3)
  val state = RegInit(sIdle)
  top.io.ctrl.aw.bits := NastiWriteAddressChannel(
    UInt(0), pcieIn.addr, UInt(log2Up(ctrlKey.dataBits/8)))
  top.io.ctrl.aw.valid := io.pcie.in.valid && pcieIn.isWr(0) && state === sIdle
  top.io.ctrl.w.bits := NastiWriteDataChannel(pcieIn.data)
  top.io.ctrl.w.valid := state === sWrite
  top.io.ctrl.ar.bits := NastiReadAddressChannel(
    UInt(0), pcieIn.addr, UInt(log2Up(ctrlKey.dataBits/8)))
  top.io.ctrl.ar.valid := io.pcie.in.valid && !pcieIn.isWr(0) && state === sIdle
  top.io.ctrl.b.ready := state === sWrAck
  io.pcie.in.ready := top.io.ctrl.ar.fire() || top.io.ctrl.b.fire()
  switch(state) {
    is(sIdle) {
      when(top.io.ctrl.aw.fire()) {
        state := sWrite
      }
    }
    is(sWrite) {
      when(top.io.ctrl.w.fire()) {
        state := sWrAck
      }
    }
    is(sWrAck) {
      when(top.io.ctrl.b.fire()) {
        state := sIdle
      }
    }
  }

  // PCIe Output
  io.pcie.out.bits := top.io.ctrl.r.bits.data
  io.pcie.out.valid := top.io.ctrl.r.valid
  top.io.ctrl.r.ready := io.pcie.out.ready

  // TODO: connect top.io.mem to UMI
}
