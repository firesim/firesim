
package midas
package models

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.util.ParameterizedBundle
import junctions._

class NastiReqChannels(implicit val p: Parameters) extends ParameterizedBundle {
  val aw = Decoupled(new NastiWriteAddressChannel)
  val w  = Decoupled(new NastiWriteDataChannel)
  val ar = Decoupled(new NastiReadAddressChannel)

  def fromNasti(n: NastiIO): Unit = {
    aw <> n.aw
    ar <> n.ar
    w  <> n.w
  }
}

object NastiReqChannels {
  def apply(nasti: NastiIO)(implicit p: Parameters): NastiReqChannels = {
    val w = Wire(new NastiReqChannels)
    w.ar <> nasti.ar
    w.aw <> nasti.aw
    w.w  <> nasti.w
    w
  }
}

class ValidNastiReqChannels(implicit val p: Parameters) extends ParameterizedBundle {
  val aw = Valid(new NastiWriteAddressChannel)
  val w  = Valid(new NastiWriteDataChannel)
  val ar = Valid(new NastiReadAddressChannel)
}

class NastiRespChannels(implicit val p: Parameters) extends ParameterizedBundle {
  val b  = Decoupled(new NastiWriteResponseChannel)
  val r  = Decoupled(new NastiReadDataChannel)
}

// Target-level interface
class EgressReq(implicit val p: Parameters) extends ParameterizedBundle
    with HasNastiParameters {
  val b = Valid(UInt(nastiWIdBits.W))
  val r = Valid(UInt(nastiRIdBits.W))
}

// Target-level interface
class EgressResp(implicit val p: Parameters) extends ParameterizedBundle {
  val bBits = Output(new NastiWriteResponseChannel)
  val bReady = Input(Bool())
  val rBits = Output(new NastiReadDataChannel)
  val rReady = Input(Bool())
}

// Contains the metadata required to track a transaction as it it requested from the egress unit
class CurrentReadResp(implicit val p: Parameters) extends ParameterizedBundle
    with HasNastiParameters {
  val id = UInt(nastiRIdBits.W)
  val len = UInt(nastiXLenBits.W)
}
class CurrentWriteResp(implicit val p: Parameters) extends ParameterizedBundle 
    with HasNastiParameters {
  val id = UInt(nastiRIdBits.W)
}

class MemModelTargetIO(implicit val p: Parameters) extends ParameterizedBundle {
  val nasti = new NastiIO
  val reset = Output(Bool())
}
