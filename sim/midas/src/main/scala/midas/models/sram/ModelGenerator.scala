package midas.models.sram

import chisel3._

trait ModelGenerator {
  val emitModel: () => Module
  val emitRTLImpl: () => Module
}
