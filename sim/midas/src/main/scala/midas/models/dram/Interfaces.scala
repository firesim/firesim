package midas
package models

import chisel3._
import chisel3.util._

import firesim.lib.nasti._

class NastiReqChannels(nastiParams: NastiParameters) extends Bundle {
  val aw = Decoupled(new NastiWriteAddressChannel(nastiParams))
  val w  = Decoupled(new NastiWriteDataChannel(nastiParams))
  val ar = Decoupled(new NastiReadAddressChannel(nastiParams))

  def fromNasti(n: NastiIO): Unit = {
    aw <> n.aw
    ar <> n.ar
    w  <> n.w
  }
}

object NastiReqChannels {
  def apply(nastiParams: NastiParameters, nasti: NastiIO): NastiReqChannels = {
    val w = Wire(new NastiReqChannels(nastiParams))
    w.ar <> nasti.ar
    w.aw <> nasti.aw
    w.w  <> nasti.w
    w
  }
}

class ValidNastiReqChannels(nastiParams: NastiParameters) extends Bundle {
  val aw = Valid(new NastiWriteAddressChannel(nastiParams))
  val w  = Valid(new NastiWriteDataChannel(nastiParams))
  val ar = Valid(new NastiReadAddressChannel(nastiParams))
}

class NastiRespChannels(nastiParams: NastiParameters) extends Bundle {
  val b = Decoupled(new NastiWriteResponseChannel(nastiParams))
  val r = Decoupled(new NastiReadDataChannel(nastiParams))
}

// Target-level interface
class EgressReq(nastiParams: NastiParameters) extends NastiBundle(nastiParams) {
  val b = Valid(UInt(nastiWIdBits.W))
  val r = Valid(UInt(nastiRIdBits.W))
}

// Target-level interface
class EgressResp(nastiParams: NastiParameters) extends Bundle {
  val bBits  = Output(new NastiWriteResponseChannel(nastiParams))
  val bReady = Input(Bool())
  val rBits  = Output(new NastiReadDataChannel(nastiParams))
  val rReady = Input(Bool())
}

// Contains the metadata required to track a transaction as it it requested from the egress unit
class CurrentReadResp(nastiParams: NastiParameters) extends NastiBundle(nastiParams) {
  val id  = UInt(nastiRIdBits.W)
  val len = UInt(nastiXLenBits.W)
}
class CurrentWriteResp(nastiParams: NastiParameters) extends NastiBundle(nastiParams) {
  val id = UInt(nastiRIdBits.W)
}

class MemModelTargetIO(nastiParams: NastiParameters) extends Bundle {
  val nasti = new NastiIO(nastiParams)
  val reset = Output(Bool())
}
