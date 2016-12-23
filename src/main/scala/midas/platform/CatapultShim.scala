package midas
package platform

import util.ParameterizedBundle // from rocketchip
import widgets._
import chisel3._
import chisel3.util._
import cde.{Parameters, Field}
import junctions._

case object PCIeWidth extends Field[Int]
case object SoftRegKey extends Field[SoftRegParam]
case class SoftRegParam(addrBits: Int, dataBits: Int)

class SoftRegReq(implicit p: Parameters) extends ParameterizedBundle()(p) {
  val addr = UInt(width=p(SoftRegKey).addrBits)
  val wdata = UInt(width=p(SoftRegKey).dataBits)
  val wr = Bool()
}

class SoftRegResp(implicit p: Parameters) extends ParameterizedBundle()(p) {
  val rdata = UInt(width=p(SoftRegKey).dataBits)
}

class SoftRegBundle(implicit p: Parameters) extends ParameterizedBundle()(p) {
  val req = Flipped(Decoupled(new SoftRegReq))
  val resp = Decoupled(new SoftRegResp)
}

class CatapultShimIO(implicit p: Parameters) extends ParameterizedBundle()(p) {
  val pcie = new SerialIO(p(PCIeWidth))
  val softreg = new SoftRegBundle
  // TODO: UMI
}

class CatapultShim(simIo: midas.core.SimWrapperIO)
                  (implicit p: Parameters) extends PlatformShim {
  val ctrlKey = p(widgets.CtrlNastiKey)
  val io = IO(new CatapultShimIO)
  val top = Module(new midas.core.FPGATop(simIo))
  val headerConsts = List(
    // "PCIE_WIDTH"      -> p(PCIeWidth),
    "MMIO_WIDTH"      -> p(SoftRegKey).dataBits / 8,
    "MEM_WIDTH"       -> 0 // Todo
  ) ++ top.headerConsts

  val sIdle :: sRead :: sWrite :: sWrAck:: Nil = Enum(UInt(), 4)
  val state = RegInit(sIdle)
  val dataSizeBits = UInt(log2Up(ctrlKey.dataBits/8))
  top.io.ctrl.aw.bits := NastiWriteAddressChannel(
    UInt(0), io.softreg.req.bits.addr << dataSizeBits, dataSizeBits)
  top.io.ctrl.aw.valid := io.softreg.req.valid && io.softreg.req.bits.wr && state === sIdle
  top.io.ctrl.ar.bits := NastiReadAddressChannel(
    UInt(0), io.softreg.req.bits.addr << dataSizeBits, dataSizeBits)
  top.io.ctrl.ar.valid := io.softreg.req.valid && !io.softreg.req.bits.wr && state === sIdle
  top.io.ctrl.w.bits := NastiWriteDataChannel(io.softreg.req.bits.wdata)
  top.io.ctrl.w.valid := state === sWrite
  io.softreg.req.ready := top.io.ctrl.ar.fire() || top.io.ctrl.w.fire()

  io.softreg.resp.bits.rdata := top.io.ctrl.r.bits.data
  io.softreg.resp.valid := top.io.ctrl.r.valid
  top.io.ctrl.r.ready := state === sRead && io.softreg.resp.ready
  top.io.ctrl.b.ready := state === sWrAck

  switch(state) {
    is(sIdle) {
      when(top.io.ctrl.ar.fire()) {
        state := sRead
      }.elsewhen(top.io.ctrl.aw.fire()) {
        state := sWrite
      }
    }
    is(sRead) {
      when(top.io.ctrl.r.fire()) {
        state := sIdle
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

  // Turn off PCIe
  io.pcie.in.ready := Bool(false)
  io.pcie.out.valid := Bool(false)

  // TODO: connect top.io.mem to UMI
}
