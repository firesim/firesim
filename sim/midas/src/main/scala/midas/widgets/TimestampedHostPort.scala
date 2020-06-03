// See LICENSE for license details.

package midas.widgets

import chisel3._

trait HasTimestampConstants {
  val timestampWidth = 64
}

class TimestampedToken[T <: Data](private val gen: T) extends Bundle with HasTimestampConstants {
  val data = Output(gen.cloneType)
  val time = Output(UInt(timestampWidth.W))
}
