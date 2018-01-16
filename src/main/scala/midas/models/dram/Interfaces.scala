
package midas
package models

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.{Parameters, Field}
import freechips.rocketchip.util.ParameterizedBundle
import junctions._

class NastiReqChannels(implicit val p: Parameters) extends ParameterizedBundle {
  val aw = Decoupled(new NastiWriteAddressChannel)
  val w  = Decoupled(new NastiWriteDataChannel)
  val ar = Decoupled(new NastiReadAddressChannel)
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
  val b = Valid(UInt(width = nastiWIdBits))
  val r = Valid(UInt(width = nastiRIdBits))
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
  val id = UInt(width = nastiRIdBits)
  val len = UInt(width = nastiXLenBits)
}
class CurrentWriteResp(implicit val p: Parameters) extends ParameterizedBundle 
    with HasNastiParameters {
  val id = UInt(width = nastiRIdBits)
}

class MemModelTargetIO(implicit val p: Parameters) extends ParameterizedBundle {
  val nasti = new NastiIO
  val reset = Output(Bool())
}
