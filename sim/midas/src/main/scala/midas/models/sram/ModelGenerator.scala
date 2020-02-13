package midas.models.sram

import chisel3._
import chisel3.util.{Mux1H, Decoupled, RegEnable, log2Ceil}
import chisel3.experimental.{DataMirror, requireIsChiselType}
import collection.immutable.ListMap

trait ModelGenerator {
  val emitModel: () => MultiIOModule
  val emitRTLImpl: () => MultiIOModule
}
